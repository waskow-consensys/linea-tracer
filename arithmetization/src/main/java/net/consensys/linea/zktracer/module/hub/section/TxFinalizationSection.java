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

package net.consensys.linea.zktracer.module.hub.section;

import static com.google.common.base.Preconditions.*;

import java.util.List;
import java.util.Set;

import net.consensys.linea.zktracer.module.hub.AccountSnapshot;
import net.consensys.linea.zktracer.module.hub.Hub;
import net.consensys.linea.zktracer.module.hub.defer.PostTransactionDefer;
import net.consensys.linea.zktracer.module.hub.fragment.DomSubStampsSubFragment;
import net.consensys.linea.zktracer.module.hub.fragment.TransactionFragment;
import net.consensys.linea.zktracer.module.hub.fragment.account.AccountFragment;
import net.consensys.linea.zktracer.module.hub.transients.DeploymentInfo;
import net.consensys.linea.zktracer.types.TransactionProcessingMetadata;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldView;

public class TxFinalizationSection extends TraceSection implements PostTransactionDefer {
  private final TransactionProcessingMetadata txMetadata;

  private AccountSnapshot senderGasRefund;
  private AccountSnapshot senderGasRefundNew;

  private AccountSnapshot coinbaseGasRefund;
  private AccountSnapshot coinbaseGasRefundNew;

  public TxFinalizationSection(Hub hub, WorldView world, boolean exceptionOrRevert) {
    super(hub, (short) 4);
    hub.defers().scheduleForEndTransaction(this);
    txMetadata = hub.txStack().current();
  }

  @Override
  public void resolveAtEndTransaction(
      Hub hub, WorldView world, Transaction tx, boolean isSuccessful) {

    checkArgument(isSuccessful == txMetadata.statusCode());

    DeploymentInfo deploymentInfo = hub.transients().conflation().deploymentInfo();
    checkArgument(
        !deploymentInfo.getDeploymentStatus(hub.coinbaseAddress),
        "The coinbase may not be under deployment");

    setSnapshots(hub, world);

    final AccountFragment senderAccountFragment =
        hub.factories()
            .accountFragment()
            .make(
                senderGasRefund,
                senderGasRefundNew,
                DomSubStampsSubFragment.standardDomSubStamps(hub.stamp(), 0)); //

    final AccountFragment coinbaseAccountFragment =
        hub.factories()
            .accountFragment()
            .makeWithTrm(
                coinbaseGasRefund,
                coinbaseGasRefundNew,
                coinbaseGasRefund.address(),
                DomSubStampsSubFragment.standardDomSubStamps(hub.stamp(), 1));

    this.addFragment(senderAccountFragment);
    this.addFragment(coinbaseAccountFragment);
    this.addFragment(TransactionFragment.prepare(hub, hub.txStack().current())); // TXN i+2
  }

  /**
   * Extracting the snapshots for the sender and the coinbase does not work as one may expect. One
   * has to start with the `New` versions of the snapshots and then deduce the `Old` versions. This
   * is due to that this method is called in the {@link
   * OperationTracer#traceEndTransaction(WorldView, Transaction, boolean, Bytes, List, long, Set,
   * long)} method, when gas refunds have already been honored, both for the sender and the
   * coinbase.
   *
   * <p>1. snapshot the coinbase, this yields coinbaseNew
   *
   * <p>2. undo the gas reward, this yields coinbase
   *
   * <p>3.1. if {@link #senderIsCoinbase(Hub)} set {@link #senderGasRefundNew} = {@link
   * #coinbaseGasRefund}.deepCopy()
   *
   * <p>3.2. else set {@link #senderGasRefundNew} = snapshot the sender
   *
   * <p>4. get sender by undoing the leftover gas refund which is already implicitly in coinbase
   *
   * <p><b>N.B.</b> The processing is independent of the success or failure of the transaction.
   */
  private void setSnapshots(Hub hub, WorldView world) {
    final Address senderAddress = txMetadata.getSender();
    final Address coinbaseAddress = hub.coinbaseAddress;

    if (senderIsCoinbase(hub)) {
      checkState(coinbaseWarmth());
    }

    coinbaseGasRefundNew =
        AccountSnapshot.canonical(hub, world, coinbaseAddress)
            .setWarmthTo(coinbaseWarmth())
            .setDeploymentInfo(hub);
    coinbaseGasRefund =
        coinbaseGasRefundNew.deepCopy().decrementBalanceBy(txMetadata.getCoinbaseReward());

    senderGasRefundNew =
        senderIsCoinbase(hub)
            ? coinbaseGasRefund.deepCopy()
            : AccountSnapshot.canonical(hub, world, senderAddress);
    senderGasRefund =
        senderGasRefundNew.deepCopy().decrementBalanceBy(txMetadata.getGasRefundInWei());
  }

  private boolean coinbaseWarmth() {
    return txMetadata.isCoinbaseWarmAtTransactionEnd();
  }

  public static boolean senderIsCoinbase(Hub hub) {
    final TransactionProcessingMetadata tx = hub.txStack().current();
    final Address senderAddress = tx.getSender();
    final Address coinbaseAddress = hub.coinbaseAddress;
    return coinbaseAddress.equals(senderAddress);
  }
}
