/*
 * Copyright ConsenSys Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package net.consensys.linea.zktracer.module.hub.section.call;

import static com.google.common.base.Preconditions.*;
import static net.consensys.linea.zktracer.module.hub.AccountSnapshot.canonical;
import static net.consensys.linea.zktracer.module.hub.fragment.scenario.CallScenarioFragment.CallScenario.*;
import static net.consensys.linea.zktracer.opcode.OpCode.CALL;
import static net.consensys.linea.zktracer.types.AddressUtils.isPrecompile;
import static net.consensys.linea.zktracer.types.Conversions.bytesToBoolean;
import static org.hyperledger.besu.datatypes.Address.*;

import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import lombok.Getter;
import lombok.Setter;
import net.consensys.linea.zktracer.module.hub.AccountSnapshot;
import net.consensys.linea.zktracer.module.hub.Factories;
import net.consensys.linea.zktracer.module.hub.Hub;
import net.consensys.linea.zktracer.module.hub.defer.ContextEntryDefer;
import net.consensys.linea.zktracer.module.hub.defer.ContextExitDefer;
import net.consensys.linea.zktracer.module.hub.defer.ContextReEntryDefer;
import net.consensys.linea.zktracer.module.hub.defer.PostOpcodeDefer;
import net.consensys.linea.zktracer.module.hub.defer.PostRollbackDefer;
import net.consensys.linea.zktracer.module.hub.defer.PostTransactionDefer;
import net.consensys.linea.zktracer.module.hub.fragment.ContextFragment;
import net.consensys.linea.zktracer.module.hub.fragment.DomSubStampsSubFragment;
import net.consensys.linea.zktracer.module.hub.fragment.account.AccountFragment;
import net.consensys.linea.zktracer.module.hub.fragment.imc.ImcFragment;
import net.consensys.linea.zktracer.module.hub.fragment.imc.MxpCall;
import net.consensys.linea.zktracer.module.hub.fragment.imc.StpCall;
import net.consensys.linea.zktracer.module.hub.fragment.imc.oob.opcodes.CallOobCall;
import net.consensys.linea.zktracer.module.hub.fragment.imc.oob.opcodes.XCallOobCall;
import net.consensys.linea.zktracer.module.hub.fragment.scenario.CallScenarioFragment;
import net.consensys.linea.zktracer.module.hub.section.TraceSection;
import net.consensys.linea.zktracer.module.hub.section.call.precompileSubsection.*;
import net.consensys.linea.zktracer.module.hub.signals.Exceptions;
import net.consensys.linea.zktracer.opcode.OpCode;
import net.consensys.linea.zktracer.runtime.callstack.CallDataInfo;
import net.consensys.linea.zktracer.runtime.callstack.CallFrame;
import net.consensys.linea.zktracer.types.EWord;
import net.consensys.linea.zktracer.types.MemorySpan;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.internal.Words;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.hyperledger.besu.evm.worldstate.WorldView;

/**
 * A {@link CallSection} first detects exceptional CALL-type instructions. Exceptional CALL's are
 * easily dealt with and require no post-processing.
 *
 * <p>Unexceptional CALL-type instructions, including aborted ones, <b>always</b> require some
 * degree of post-processing. For one, they are <b>all</b> rollback sensitive as it pertains to
 * value transfers and warmth. As such everything gets scheduled for post rollback.
 *
 * <p>We also need to schedule unexceptional {@link CallSection}'s for post-transaction resolution.
 * Indeed, the following must always be performed, in that order, at transaction end:
 *
 * <p>- append the precompile subsection (if applicable)
 *
 * <p>- append the final context fragment
 */
public class CallSection extends TraceSection
    implements PostOpcodeDefer,
        ContextEntryDefer,
        ContextExitDefer,
        ContextReEntryDefer,
        PostRollbackDefer,
        PostTransactionDefer {

  private static final Map<Address, BiFunction<Hub, CallSection, PrecompileSubsection>>
      ADDRESS_TO_PRECOMPILE =
          Map.of(
              ECREC, EllipticCurvePrecompileSubsection::new,
              SHA256, ShaTwoOrRipemdSubSection::new,
              RIPEMD160, ShaTwoOrRipemdSubSection::new,
              ID, IdentitySubsection::new,
              MODEXP, ModexpSubsection::new,
              ALTBN128_ADD, EllipticCurvePrecompileSubsection::new,
              ALTBN128_MUL, EllipticCurvePrecompileSubsection::new,
              ALTBN128_PAIRING, EllipticCurvePrecompileSubsection::new,
              BLAKE2B_F_COMPRESSION, BlakeSubsection::new);

  public Optional<Address> precompileAddress;

  // row i+0
  private final CallScenarioFragment scenarioFragment = new CallScenarioFragment();

  public boolean isAbortingScenario() {
    return scenarioFragment.getScenario().isAbortingScenario();
  }

  // last row
  @Setter private ContextFragment finalContextFragment;

  private Address callerAddress;
  private Address calleeAddress;
  private Bytes rawCalleeAddress;
  final ImcFragment firstImcFragment;

  // Just before the CALL Opcode
  private AccountSnapshot preOpcodeCallerSnapshot;
  private AccountSnapshot preOpcodeCalleeSnapshot;

  // Just after the CALL Opcode
  private AccountSnapshot postOpcodeCallerSnapshot;
  private AccountSnapshot postOpcodeCalleeSnapshot;

  // Just before re-entry
  private AccountSnapshot childContextExitCallerSnapshot;
  private AccountSnapshot childContextExitCalleeSnapshot;

  // Just after re-entry
  private AccountSnapshot reEntryCallerSnapshot;
  private AccountSnapshot reEntryCalleeSnapshot;

  private final OpCode opCode;
  private Wei value;

  private AccountSnapshot postRollbackCalleeSnapshot;
  private AccountSnapshot postRollbackCallerSnapshot;

  public StpCall stpCall;
  private PrecompileSubsection precompileSubsection;

  @Getter private MemorySpan returnAtMemorySpan;
  @Getter private CallDataInfo callDataInfo;

  public CallSection(Hub hub, MessageFrame frame) {
    super(hub, maxNumberOfLines(hub));

    opCode = hub.opCode();

    final short exceptions = hub.pch().exceptions();

    // row i + 1
    final ContextFragment currentContextFragment = ContextFragment.readCurrentContextData(hub);
    // row i + 2
    firstImcFragment = ImcFragment.empty(hub);

    this.addStackAndFragments(hub, scenarioFragment, currentContextFragment, firstImcFragment);

    if (Exceptions.any(exceptions)) {
      scenarioFragment.setScenario(CALL_EXCEPTION);
      final XCallOobCall oobCall = new XCallOobCall();
      firstImcFragment.callOob(oobCall);
    }

    // STATICX cases
    if (Exceptions.staticFault(exceptions)) {
      return;
    }

    final MxpCall mxpCall = new MxpCall(hub);
    firstImcFragment.callMxp(mxpCall);
    checkArgument(mxpCall.mxpx == Exceptions.memoryExpansionException(exceptions));

    // MXPX case
    if (Exceptions.memoryExpansionException(exceptions)) {
      return;
    }

    stpCall = new StpCall(hub, mxpCall.gasMxp);
    firstImcFragment.callStp(stpCall);
    checkArgument(
        stpCall.outOfGasException() == Exceptions.outOfGasException(exceptions),
        String.format(
            "The STP and the HUB have conflicting predictions of an OOGX\n\t\tHUB_STAMP = %s",
            hubStamp()));

    final CallFrame currentFrame = hub.currentFrame();
    callerAddress = frame.getRecipientAddress();
    rawCalleeAddress = frame.getStackItem(1);
    calleeAddress = Address.extract(EWord.of(rawCalleeAddress));

    preOpcodeCallerSnapshot = canonical(hub, callerAddress);
    preOpcodeCalleeSnapshot = canonical(hub, calleeAddress);

    // OOGX case
    if (Exceptions.outOfGasException(exceptions)) {
      this.oogXCall(hub);
      return;
    }

    // The CALL is now unexceptional
    checkArgument(Exceptions.none(exceptions));
    currentFrame.childSpanningSection(this);

    final boolean callHasValueArgument = currentFrame.opCode().callHasValueArgument();

    // the call data span and ``return at'' spans are only required once the CALL is unexceptional
    returnAtMemorySpan = returnAtMemorySpan(frame, callHasValueArgument);
    callDataInfo =
        new CallDataInfo(
            frame, callDataSpan(frame, callHasValueArgument), currentFrame.contextNumber());

    value =
        callHasValueArgument
            ? Wei.of(currentFrame.frame().getStackItem(2).toUnsignedBigInteger())
            : Wei.ZERO;

    final CallOobCall oobCall = new CallOobCall();
    firstImcFragment.callOob(oobCall);

    final boolean aborts = hub.pch().abortingConditions().any();
    checkArgument(oobCall.isAbortingCondition() == aborts);

    hub.defers().scheduleForPostRollback(this, currentFrame);
    hub.defers().scheduleForPostTransaction(this);

    // The CALL is now unexceptional and un-aborted
    refineUndefinedScenario(hub);
    CallScenarioFragment.CallScenario scenario = scenarioFragment.getScenario();
    switch (scenario) {
      case CALL_ABORT_WONT_REVERT -> abortingCall(hub);
      case CALL_EOA_UNDEFINED -> eoaProcessing(hub);
      case CALL_PRC_UNDEFINED -> prcProcessing(hub);
      case CALL_SMC_UNDEFINED -> smcProcessing(hub, frame);
      default -> throw new RuntimeException("Illegal CALL scenario");
    }
  }

  private static short maxNumberOfLines(final Hub hub) {
    // 99 % of the time this number of rows will be sufficient
    if (Exceptions.any(hub.pch().exceptions())) {
      return 8;
    }
    if (hub.pch().abortingConditions().any()) {
      return 9;
    }
    return 12; // 12 = 2 (stack) + 5 (CALL prequel) + 5 (successful PRC, except BLAKE and MODEXP)
  }

  private void oogXCall(Hub hub) {

    final Factories factories = hub.factories();
    final AccountFragment callerAccountFragment =
        factories
            .accountFragment()
            .make(
                preOpcodeCallerSnapshot,
                preOpcodeCallerSnapshot,
                DomSubStampsSubFragment.standardDomSubStamps(this.hubStamp(), 0));

    final AccountFragment calleeAccountFragment =
        factories
            .accountFragment()
            .makeWithTrm(
                preOpcodeCalleeSnapshot,
                preOpcodeCalleeSnapshot,
                rawCalleeAddress,
                DomSubStampsSubFragment.standardDomSubStamps(this.hubStamp(), 1));

    this.addFragments(callerAccountFragment, calleeAccountFragment);
  }

  private void abortingCall(Hub hub) {

    postOpcodeCallerSnapshot = preOpcodeCallerSnapshot.deepCopy();
    postOpcodeCalleeSnapshot = preOpcodeCalleeSnapshot.deepCopy().turnOnWarmth();
    final Factories factories = hub.factories();
    final AccountFragment readingCallerAccount =
        factories
            .accountFragment()
            .make(
                preOpcodeCallerSnapshot,
                postOpcodeCallerSnapshot,
                DomSubStampsSubFragment.standardDomSubStamps(this.hubStamp(), 0));

    final AccountFragment readingCalleeAccountAndWarmth =
        factories
            .accountFragment()
            .makeWithTrm(
                preOpcodeCalleeSnapshot,
                postOpcodeCalleeSnapshot,
                rawCalleeAddress,
                DomSubStampsSubFragment.standardDomSubStamps(this.hubStamp(), 1));
    finalContextFragment = ContextFragment.nonExecutionProvidesEmptyReturnData(hub);
    this.addFragments(readingCallerAccount, readingCalleeAccountAndWarmth);
    hub.defers().scheduleForPostExecution(this);
    // we immediately reap the call stipend
    commonValues.collectChildStipend(hub);
  }

  /**
   * Sets the scenario to the relevant undefined variant, i.e. either
   *
   * <p>- {@link
   * net.consensys.linea.zktracer.module.hub.fragment.scenario.CallScenarioFragment.CallScenario#CALL_PRC_UNDEFINED}
   *
   * <p>- {@link
   * net.consensys.linea.zktracer.module.hub.fragment.scenario.CallScenarioFragment.CallScenario#CALL_SMC_UNDEFINED}
   *
   * <p>- {@link
   * net.consensys.linea.zktracer.module.hub.fragment.scenario.CallScenarioFragment.CallScenario#CALL_EOA_UNDEFINED}
   *
   * <p>depending on the address.
   *
   * @param hub
   */
  private void refineUndefinedScenario(Hub hub) {

    final boolean aborts = hub.pch().abortingConditions().any();
    if (aborts) {
      scenarioFragment.setScenario(CALL_ABORT_WONT_REVERT);
      return;
    }

    final WorldUpdater world = hub.currentFrame().frame().getWorldUpdater();
    if (isPrecompile(calleeAddress)) {
      precompileAddress = Optional.of(calleeAddress);
      scenarioFragment.setScenario(CALL_PRC_UNDEFINED);

      precompileSubsection =
          ADDRESS_TO_PRECOMPILE.get(preOpcodeCalleeSnapshot.address()).apply(hub, this);
    } else {
      Optional.ofNullable(world.get(calleeAddress))
          .ifPresentOrElse(
              account -> {
                scenarioFragment.setScenario(
                    account.hasCode() ? CALL_SMC_UNDEFINED : CALL_EOA_UNDEFINED);
              },
              () -> {
                scenarioFragment.setScenario(CALL_EOA_UNDEFINED);
              });
    }
  }

  private void eoaProcessing(Hub hub) {
    hub.defers().scheduleForContextReEntry(this, hub.currentFrame());
    commonValues.collectChildStipend(hub);
    finalContextFragment = ContextFragment.nonExecutionProvidesEmptyReturnData(hub);
  }

  private void smcProcessing(Hub hub, MessageFrame frame) {
    final CallFrame currentFrame = hub.currentFrame();
    hub.defers().scheduleForContextEntry(this);
    hub.defers().scheduleForContextExit(this, hub.callStack().futureId());
    hub.defers().scheduleForContextReEntry(this, currentFrame);

    hub.defers().scheduleForContextReEntry(firstImcFragment, currentFrame);

    this.commonValues.payGasPaidOutOfPocket(hub);
    finalContextFragment = ContextFragment.initializeNewExecutionContext(hub);
    hub.romLex().callRomLex(frame);
  }

  private void prcProcessing(Hub hub) {
    hub.defers().scheduleForContextEntry(this);
    hub.defers().scheduleForContextReEntry(this, hub.currentFrame());
  }

  @Override
  public void resolvePostExecution(
      Hub hub, MessageFrame frame, Operation.OperationResult operationResult) {
    // we unlatched the stack after a CALL if and only if we don't "contextEnter" the CALL.
    hub.unlatchStack(frame, this);
  }

  @Override
  public void resolveUponContextEntry(Hub hub) {

    CallScenarioFragment.CallScenario scenario = scenarioFragment.getScenario();
    checkState(scenario == CALL_SMC_UNDEFINED | scenario == CALL_PRC_UNDEFINED);

    postOpcodeCallerSnapshot = preOpcodeCallerSnapshot.deepCopy();
    postOpcodeCalleeSnapshot = preOpcodeCalleeSnapshot.deepCopy().turnOnWarmth();

    if (opCode == CALL) {
      postOpcodeCallerSnapshot.decrementBalanceBy(value);
      postOpcodeCalleeSnapshot.incrementBalanceBy(value);
    }

    // we may be doing more stuff here later
    if (scenarioFragment.getScenario() == CALL_PRC_UNDEFINED) {
      return;
    }

    if (isNonzeroValueSelfCall()) {
      checkState(scenarioFragment.getScenario() == CALL_SMC_UNDEFINED);
      preOpcodeCalleeSnapshot = postOpcodeCallerSnapshot;
      postOpcodeCalleeSnapshot = preOpcodeCallerSnapshot;
    }

    final Factories factories = hub.factories();
    final AccountFragment firstCallerAccountFragment =
        factories
            .accountFragment()
            .make(
                preOpcodeCallerSnapshot,
                postOpcodeCallerSnapshot,
                DomSubStampsSubFragment.standardDomSubStamps(this.hubStamp(), 0));

    final AccountFragment firstCalleeAccountFragment =
        factories
            .accountFragment()
            .makeWithTrm(
                preOpcodeCalleeSnapshot,
                postOpcodeCalleeSnapshot,
                rawCalleeAddress,
                DomSubStampsSubFragment.standardDomSubStamps(this.hubStamp(), 1));

    firstCalleeAccountFragment.requiresRomlex(true);

    this.addFragments(firstCallerAccountFragment, firstCalleeAccountFragment);
  }

  /** Resolution happens as the child context is about to terminate. */
  @Override
  public void resolveUponContextExit(Hub hub, CallFrame frame) {
    checkArgument(scenarioFragment.getScenario() == CALL_SMC_UNDEFINED);

    childContextExitCallerSnapshot = canonical(hub, preOpcodeCallerSnapshot.address());
    childContextExitCalleeSnapshot = canonical(hub, preOpcodeCalleeSnapshot.address());
  }

  @Override
  public void resolveAtContextReEntry(Hub hub, CallFrame frame) {
    // TODO: what follows assumes that the caller's stack has been updated
    //  to contain the success bit of the call at traceContextReEntry.
    //  See issue #872.
    // The callSuccess will only be set
    // if the call is acted upon i.e. if the call is un-exceptional and un-aborted
    final boolean successBit = bytesToBoolean(hub.messageFrame().getStackItem(0));

    reEntryCallerSnapshot = canonical(hub, callerAddress);
    reEntryCalleeSnapshot = canonical(hub, calleeAddress);

    switch (scenarioFragment.getScenario()) {
      case CALL_EOA_UNDEFINED -> {
        checkState(successBit);
        scenarioFragment.setScenario(CALL_EOA_SUCCESS_WONT_REVERT);
        emptyCodeFirstCoupleOfAccountFragments(hub);
      }

      case CALL_PRC_UNDEFINED -> {
        if (successBit) {
          scenarioFragment.setScenario(CALL_PRC_SUCCESS_WONT_REVERT);
        } else {
          scenarioFragment.setScenario(CALL_PRC_FAILURE);
        }
        emptyCodeFirstCoupleOfAccountFragments(hub);

        CallFrame prcFrame = hub.callStack().getById(frame.childFramesId().getLast());
        finalContextFragment =
            ContextFragment.updateReturnData(
                hub, prcFrame.contextNumber(), prcFrame.outputDataSpan());
      }

      case CALL_SMC_UNDEFINED -> {

        // CALL_SMC_SUCCESS_XXX case
        if (successBit) {
          scenarioFragment.setScenario(CALL_SMC_SUCCESS_WONT_REVERT);
          return;
        }

        AccountSnapshot beforeFailureCallerSnapshot =
            postOpcodeCallerSnapshot.deepCopy().setDeploymentInfo(hub);
        AccountSnapshot afterFailureCallerSnapshot =
            preOpcodeCallerSnapshot.deepCopy().setDeploymentInfo(hub);
        AccountSnapshot beforeFailureCalleeSnapshot =
            postOpcodeCalleeSnapshot.deepCopy().setDeploymentInfo(hub);
        AccountSnapshot afterFailureCalleeSnapshot =
            preOpcodeCalleeSnapshot.deepCopy().setDeploymentInfo(hub).turnOnWarmth();

        // CALL_SMC_FAILURE_XXX case
        scenarioFragment.setScenario(CALL_SMC_FAILURE_WONT_REVERT);

        if (isNonzeroValueSelfCall()) {
          childContextExitCallerSnapshot.decrementBalanceBy(value);
          reEntryCalleeSnapshot.decrementBalanceBy(value);
        }

        int childId = hub.currentFrame().childFramesId().getLast();
        CallFrame childFrame = hub.callStack().getById(childId);
        int childContextRevertStamp = childFrame.revertStamp();

        final AccountFragment postReEntryCallerAccountFragment =
            hub.factories()
                .accountFragment()
                .make(
                    beforeFailureCallerSnapshot,
                    afterFailureCallerSnapshot,
                    DomSubStampsSubFragment.revertsWithChildDomSubStamps(
                        this.hubStamp(), childContextRevertStamp, 2));

        final AccountFragment postReEntryCalleeAccountFragment =
            hub.factories()
                .accountFragment()
                .make(
                    beforeFailureCalleeSnapshot,
                    afterFailureCalleeSnapshot,
                    DomSubStampsSubFragment.revertsWithChildDomSubStamps(
                        this.hubStamp(), childContextRevertStamp, 3));

        this.addFragments(postReEntryCallerAccountFragment, postReEntryCalleeAccountFragment);
      }

      default -> throw new IllegalArgumentException("Illegal CALL scenario");
    }
  }

  @Override
  public void resolveUponRollback(Hub hub, MessageFrame messageFrame, CallFrame callFrame) {
    final Factories factory = hub.factories();
    postRollbackCalleeSnapshot = canonical(hub, calleeAddress);
    postRollbackCallerSnapshot = canonical(hub, callerAddress);

    final CallScenarioFragment.CallScenario callScenario = scenarioFragment.getScenario();
    switch (callScenario) {
      case CALL_ABORT_WONT_REVERT -> completeAbortWillRevert(hub, factory);
      case CALL_EOA_SUCCESS_WONT_REVERT -> completeEoaSuccessWillRevert(factory);
      case CALL_SMC_FAILURE_WONT_REVERT -> completeSmcFailureWillRevert(factory);
      case CALL_SMC_SUCCESS_WONT_REVERT,
          CALL_PRC_SUCCESS_WONT_REVERT -> completeSmcOrPrcSuccessWillRevert(factory);
      case CALL_PRC_FAILURE -> {
        // Note: no undoing required
        //  - account snapshots were taken with value transfers undone
        //  - precompiles are warm by definition so no warmth undoing required
        return;
      }
      default -> throw new IllegalArgumentException("Illegal CALL scenario");
    }
  }

  @Override
  public void resolvePostTransaction(
      Hub hub, WorldView state, Transaction tx, boolean isSuccessful) {

    final CallScenarioFragment.CallScenario scenario = scenarioFragment.getScenario();

    checkArgument(
        scenario.noLongerUndefined(),
        String.format(
            "Call scenario = %s, HUB_STAMP = %s, successBit = %s",
            scenarioFragment.getScenario(), this.hubStamp(), isSuccessful));

    if (scenario.isPrcCallScenario()) {
      this.addFragments(precompileSubsection.fragments());
    }

    this.addFragment(finalContextFragment);
  }

  private void completeAbortWillRevert(Hub hub, Factories factory) {
    scenarioFragment.setScenario(CALL_ABORT_WILL_REVERT);
    AccountSnapshot preRollBackCalleeSnapshot =
        postOpcodeCalleeSnapshot.deepCopy().setDeploymentInfo(hub);
    AccountSnapshot postRollBackCalleeSnapshot =
        preOpcodeCalleeSnapshot.deepCopy().setDeploymentInfo(hub);
    final AccountFragment undoingCalleeAccountFragment =
        factory
            .accountFragment()
            .make(
                preRollBackCalleeSnapshot,
                postRollBackCalleeSnapshot,
                DomSubStampsSubFragment.revertWithCurrentDomSubStamps(
                    this.hubStamp(), this.revertStamp(), 2));
    this.addFragment(undoingCalleeAccountFragment);
  }

  private void completeEoaSuccessWillRevert(Factories factory) {
    scenarioFragment.setScenario(CALL_EOA_SUCCESS_WILL_REVERT);

    final AccountSnapshot callerRightBeforeRollBack =
        reEntryCallerSnapshot.deepCopy().copyDeploymentInfoFrom(postRollbackCallerSnapshot);
    final AccountSnapshot callerRightAfterRollBack =
        preOpcodeCallerSnapshot.deepCopy().copyDeploymentInfoFrom(postRollbackCallerSnapshot);

    final AccountSnapshot calleeRightBeforeRollBack =
        reEntryCalleeSnapshot.deepCopy().copyDeploymentInfoFrom(postRollbackCalleeSnapshot);
    final AccountSnapshot calleeRightAfterRollBack =
        preOpcodeCalleeSnapshot.deepCopy().copyDeploymentInfoFrom(postRollbackCalleeSnapshot);

    final AccountFragment undoingCallerAccountFragment =
        factory
            .accountFragment()
            .make(
                callerRightBeforeRollBack,
                callerRightAfterRollBack,
                DomSubStampsSubFragment.revertWithCurrentDomSubStamps(
                    this.hubStamp(), this.revertStamp(), 2));

    final AccountFragment undoingCalleeAccountFragment =
        factory
            .accountFragment()
            .make(
                calleeRightBeforeRollBack,
                calleeRightAfterRollBack,
                DomSubStampsSubFragment.revertWithCurrentDomSubStamps(
                    this.hubStamp(), this.revertStamp(), 3));

    this.addFragments(undoingCallerAccountFragment, undoingCalleeAccountFragment);
  }

  private void completeSmcFailureWillRevert(Factories factory) {
    scenarioFragment.setScenario(CALL_SMC_FAILURE_WILL_REVERT);

    // this (should) work for both self calls and foreign address calls
    final AccountFragment undoingCalleeWarmthAccountFragment =
        factory
            .accountFragment()
            .make(
                reEntryCalleeSnapshot,
                postRollbackCalleeSnapshot,
                DomSubStampsSubFragment.revertWithCurrentDomSubStamps(
                    this.hubStamp(), this.revertStamp(), 4));

    this.addFragment(undoingCalleeWarmthAccountFragment);
  }

  private void completeSmcOrPrcSuccessWillRevert(Factories factory) {

    final CallScenarioFragment.CallScenario callScenario = scenarioFragment.getScenario();
    if (callScenario == CALL_SMC_SUCCESS_WONT_REVERT) {
      scenarioFragment.setScenario(CALL_SMC_SUCCESS_WILL_REVERT);
    } else {
      scenarioFragment.setScenario(CALL_PRC_SUCCESS_WILL_REVERT);
    }

    final AccountSnapshot callerRightBeforeRollBack =
        postOpcodeCallerSnapshot.deepCopy().copyDeploymentInfoFrom(postRollbackCallerSnapshot);
    final AccountSnapshot callerRightAfterRollBack =
        preOpcodeCallerSnapshot.deepCopy().copyDeploymentInfoFrom(postRollbackCallerSnapshot);

    final AccountSnapshot calleeRightBeforeRollBack =
        postOpcodeCalleeSnapshot.deepCopy().copyDeploymentInfoFrom(postRollbackCalleeSnapshot);
    final AccountSnapshot calleeRightAfterRollBack =
        preOpcodeCalleeSnapshot.deepCopy().copyDeploymentInfoFrom(postRollbackCalleeSnapshot);

    final AccountFragment undoingCallerAccountFragment =
        factory
            .accountFragment()
            .make(
                callerRightBeforeRollBack,
                callerRightAfterRollBack,
                DomSubStampsSubFragment.revertWithCurrentDomSubStamps(
                    this.hubStamp(), this.revertStamp(), 2));
    final AccountFragment undoingCalleeAccountFragment =
        factory
            .accountFragment()
            .make(
                calleeRightBeforeRollBack,
                calleeRightAfterRollBack,
                DomSubStampsSubFragment.revertWithCurrentDomSubStamps(
                    this.hubStamp(), this.revertStamp(), 3));

    this.addFragments(undoingCallerAccountFragment, undoingCalleeAccountFragment);
  }

  private void emptyCodeFirstCoupleOfAccountFragments(final Hub hub) {
    final Factories factories = hub.factories();
    final AccountFragment firstCallerAccountFragment =
        factories
            .accountFragment()
            .make(
                preOpcodeCallerSnapshot,
                reEntryCallerSnapshot,
                DomSubStampsSubFragment.standardDomSubStamps(this.hubStamp(), 0));

    final AccountFragment firstCalleeAccountFragment =
        factories
            .accountFragment()
            .makeWithTrm(
                preOpcodeCalleeSnapshot,
                reEntryCalleeSnapshot,
                rawCalleeAddress,
                DomSubStampsSubFragment.standardDomSubStamps(this.hubStamp(), 1));

    this.addFragments(firstCallerAccountFragment, firstCalleeAccountFragment);
  }

  private MemorySpan callDataSpan(MessageFrame frame, boolean callHasValueArgument) {
    final long callDataSize =
        callHasValueArgument
            ? Words.clampedToLong(frame.getStackItem(4))
            : Words.clampedToLong(frame.getStackItem(3));

    if (callDataSize == 0) {
      return MemorySpan.empty();
    }

    final long returnAtOffset =
        callHasValueArgument
            ? Words.clampedToLong(frame.getStackItem(3))
            : Words.clampedToLong(frame.getStackItem(2));
    return MemorySpan.fromStartLength(returnAtOffset, callDataSize);
  }

  /**
   * The {@link #returnAtMemorySpan(MessageFrame, boolean)} method implements the spec logic for
   * defining the ``returnAtMemorySpan`` of a CALL. The main point being: if its capacity is zero we
   * require that {@link MemorySpan} to be {@link MemorySpan#empty()}.
   *
   * @param frame
   * @param callHasValueArgument
   * @return
   */
  private MemorySpan returnAtMemorySpan(MessageFrame frame, boolean callHasValueArgument) {
    final long returnAtCapacity =
        callHasValueArgument
            ? Words.clampedToLong(frame.getStackItem(6))
            : Words.clampedToLong(frame.getStackItem(5));

    if (returnAtCapacity == 0) {
      return MemorySpan.empty();
    }

    final long returnAtOffset =
        callHasValueArgument
            ? Words.clampedToLong(frame.getStackItem(5))
            : Words.clampedToLong(frame.getStackItem(4));
    return MemorySpan.fromStartLength(returnAtOffset, returnAtCapacity);
  }

  private boolean isSelfCall() {
    checkState(scenarioFragment.getScenario().isIndefiniteSmcCallScenario());
    return calleeAddress.equals(callerAddress);
  }

  private boolean isNonzeroValueSelfCall() {
    checkState(scenarioFragment.getScenario().isIndefiniteSmcCallScenario());
    return isSelfCall() && !value.isZero();
  }
}
