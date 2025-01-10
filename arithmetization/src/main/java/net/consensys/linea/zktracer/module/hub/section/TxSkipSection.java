/*
 * Copyright Consensys Software Inc.
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
import static net.consensys.linea.zktracer.module.hub.AccountSnapshot.canonical;
import static net.consensys.linea.zktracer.types.AddressUtils.isPrecompile;

import java.math.BigInteger;

import net.consensys.linea.zktracer.module.hub.AccountSnapshot;
import net.consensys.linea.zktracer.module.hub.Hub;
import net.consensys.linea.zktracer.module.hub.defer.PostTransactionDefer;
import net.consensys.linea.zktracer.module.hub.fragment.DomSubStampsSubFragment;
import net.consensys.linea.zktracer.module.hub.fragment.TransactionFragment;
import net.consensys.linea.zktracer.module.hub.fragment.account.AccountFragment;
import net.consensys.linea.zktracer.module.hub.transients.Transients;
import net.consensys.linea.zktracer.types.TransactionProcessingMetadata;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.worldstate.WorldView;

/**
 * SkippedTransaction latches data at the pre-execution of the transaction data that will be used
 * later, through a {@link PostTransactionDefer}, to generate the trace chunks required for the
 * proving of a pure transaction.
 */
public class TxSkipSection extends TraceSection implements PostTransactionDefer {

  final TransactionProcessingMetadata txMetadata;

  Address senderAddress;
  AccountSnapshot sender;
  AccountSnapshot senderNew;

  Address recipientAddress;
  AccountSnapshot recipient;
  AccountSnapshot recipientNew;

  Address coinbaseAddress;
  AccountSnapshot coinbase;
  AccountSnapshot coinbaseNew;

  public TxSkipSection(
      Hub hub,
      WorldView world,
      TransactionProcessingMetadata transactionProcessingMetadata,
      Transients transients) {
    super(hub, (short) 4);
    hub.defers().scheduleForEndTransaction(this);

    txMetadata = transactionProcessingMetadata;
    senderAddress = txMetadata.getBesuTransaction().getSender();
    recipientAddress = txMetadata.getEffectiveRecipient();

    sender = canonical(hub, world, senderAddress, isPrecompile(senderAddress));
    recipient = canonical(hub, world, recipientAddress, isPrecompile(recipientAddress));

    // arithmetization restriction
    checkArgument(
        !isPrecompile(recipientAddress),
        "Arithmetization restriction: recipient address is a precompile.");

    // sanity check + EIP-3607
    checkArgument(world.get(senderAddress) != null, "Sender account must exists");
    checkArgument(!world.get(senderAddress).hasCode(), "Sender account must not have code");

    // deployments are local to a transaction, every address should have deploymentStatus == false
    // at the start of every transaction
    checkArgument(!hub.deploymentStatusOf(senderAddress));
    checkArgument(!hub.deploymentStatusOf(recipientAddress));

    // the updated deployment info appears in the "updated" account fragment
    if (txMetadata.isDeployment()) {
      transients.conflation().deploymentInfo().newDeploymentSansExecutionAt(recipientAddress);
    }
  }

  /**
   * The coinbase address isn't necessarily that of the block. We do, however, obtain it via the
   * {@link MessageFrame} of the hub.
   */
  public void coinbaseSnapshots(Hub hub, MessageFrame frame) {
    coinbaseAddress = frame.getMiningBeneficiary();
    coinbase =
        canonical(hub, frame.getWorldUpdater(), coinbaseAddress, isPrecompile(coinbaseAddress));
    checkArgument(!hub.deploymentStatusOf(coinbaseAddress));
  }

  @Override
  public void resolveAtEndTransaction(
      Hub hub, WorldView world, Transaction tx, boolean statusCode) {

    checkArgument(statusCode, "TX_SKIP transactions should be successful");
    checkArgument(txMetadata.statusCode(), "meta data suggests an unsuccessful TX_SKIP");

    // may have to be modified in case of address collision
    senderNew = canonical(hub, world, sender.address(), isPrecompile(sender.address()));
    recipientNew = canonical(hub, world, recipient.address(), isPrecompile(recipient.address()));
    coinbaseNew = canonical(hub, world, coinbase.address(), isPrecompile(recipient.address()));

    final Wei value = (Wei) txMetadata.getBesuTransaction().getValue();

    if (senderAddressCollision()) {
      BigInteger gasUsed = BigInteger.valueOf(txMetadata.getGasUsed());
      BigInteger gasPrice = BigInteger.valueOf(txMetadata.getEffectiveGasPrice());
      BigInteger gasCost = gasUsed.multiply(gasPrice);
      senderNew =
          sender
              .deepCopy()
              .decrementBalanceBy(value)
              .decrementBalanceBy(Wei.of(gasCost))
              .raiseNonceByOne();
    }

    if (senderIsRecipient()) {
      recipient = senderNew.deepCopy();
      recipientNew = recipient.deepCopy().incrementBalanceBy(value);
    } else {
      if (recipientIsCoinbase()) {
        recipientNew = coinbaseNew.deepCopy().decrementBalanceBy(txMetadata.getCoinbaseReward());
        recipient = recipientNew.deepCopy().decrementBalanceBy(value);
      }
    }

    if (coinbaseAddressCollision()) {
      coinbase = coinbaseNew.deepCopy().decrementBalanceBy(txMetadata.getCoinbaseReward());
    }

    // "sender" account fragment
    final AccountFragment senderAccountFragment =
        hub.factories()
            .accountFragment()
            .makeWithTrm(
                sender,
                senderNew,
                sender.address(),
                DomSubStampsSubFragment.standardDomSubStamps(hub.stamp(), 0));

    // "recipient" account fragment
    final AccountFragment recipientAccountFragment =
        hub.factories()
            .accountFragment()
            .makeWithTrm(
                recipient,
                recipientNew,
                recipient.address(),
                DomSubStampsSubFragment.standardDomSubStamps(hub.stamp(), 1));

    // "coinbase" account fragment
    final AccountFragment coinbaseAccountFragment =
        hub.factories()
            .accountFragment()
            .makeWithTrm(
                coinbase,
                coinbaseNew,
                coinbase.address(),
                DomSubStampsSubFragment.standardDomSubStamps(hub.stamp(), 2));

    // transaction fragment
    final TransactionFragment transactionFragment =
        TransactionFragment.prepare(hub, hub.txStack().current());

    this.addFragment(senderAccountFragment);
    this.addFragment(recipientAccountFragment);
    this.addFragment(coinbaseAccountFragment);
    this.addFragment(transactionFragment);
  }

  public boolean senderIsRecipient() {
    return senderAddress.equals(recipientAddress);
  }

  public boolean senderIsCoinbase() {
    return senderAddress.equals(coinbaseAddress);
  }

  public boolean recipientIsCoinbase() {
    return recipientAddress.equals(coinbaseAddress);
  }

  public boolean senderAddressCollision() {
    return senderIsRecipient() || senderIsCoinbase();
  }

  public boolean recipientAddressCollision() {
    return senderIsRecipient() || recipientIsCoinbase();
  }

  public boolean coinbaseAddressCollision() {
    return senderIsCoinbase() || recipientIsCoinbase();
  }

  public boolean addressCollision() {
    return senderIsRecipient() || senderIsCoinbase() || recipientIsCoinbase();
  }

  public boolean noAddressCollisions() {
    return !addressCollision();
  }
}
