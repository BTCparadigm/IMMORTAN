package immortan.fsm

import fr.acinq.eclair._
import fr.acinq.eclair.wire._
import immortan.fsm.IncomingPaymentProcessor._
import immortan.ChannelMaster.{OutgoingAdds, PreimageTry, ReasonableLocals, ReasonableTrampolines}
import immortan.{ChannelMaster, InFlightPayments, LNParams, PaymentInfo, PaymentStatus}
import fr.acinq.eclair.channel.{ReasonableLocal, ReasonableTrampoline}
import fr.acinq.eclair.transactions.RemoteFulfill
import fr.acinq.eclair.router.RouteCalculation
import fr.acinq.eclair.payment.IncomingPacket
import immortan.fsm.PaymentFailure.Failures
import fr.acinq.bitcoin.Crypto.PublicKey
import immortan.crypto.Tools.Any2Some
import fr.acinq.bitcoin.ByteVector32
import immortan.crypto.StateMachine
import scala.util.Success


object IncomingPaymentProcessor {
  final val SHUTDOWN = "incoming-processor-shutdown"
  final val FINALIZING = "incoming-processor-finalizing"
  final val RECEIVING = "incoming-processor-receiving"
  final val SENDING = "incoming-processor-sending"
  final val CMDTimeout = "cmd-timeout"
}

sealed trait IncomingPaymentProcessor extends StateMachine[IncomingProcessorData] { me =>
  lazy val tuple: (FullPaymentTag, IncomingPaymentProcessor) = (fullTag, me)
  val fullTag: FullPaymentTag
}

// LOCAL RECEIVER

sealed trait IncomingProcessorData

case class IncomingRevealed(preimage: ByteVector32) extends IncomingProcessorData
case class IncomingAborted(failure: Option[FailureMessage] = None) extends IncomingProcessorData

class IncomingPaymentReceiver(val fullTag: FullPaymentTag, cm: ChannelMaster) extends IncomingPaymentProcessor {
  def gotSome(adds: ReasonableLocals): Boolean = adds.nonEmpty && amountIn(adds) >= adds.head.packet.payload.totalAmount
  def askCovered(adds: ReasonableLocals, info: PaymentInfo): Boolean = amountIn(adds) >= info.amountOrMin
  def amountIn(adds: ReasonableLocals): MilliSatoshi = adds.map(_.add.amountMsat).sum

  require(fullTag.tag == PaymentTagTlv.FINAL_INCOMING)
  delayedCMDWorker.replaceWork(CMDTimeout)
  become(freshData = null, RECEIVING)

  def doProcess(msg: Any): Unit = (msg, data, state) match {
    case (inFlight: InFlightPayments, _, RECEIVING | FINALIZING) if !inFlight.in.contains(fullTag) =>
      // We have previously failed or fulfilled an incoming payment and all parts have been cleared
      cm.inProcessors -= fullTag
      become(null, SHUTDOWN)

    case (inFlight: InFlightPayments, null, RECEIVING) =>
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      // Important: when creating new invoice we SPECIFICALLY DO NOT put a preimage into preimage storage
      // we only do that for final incoming payment once it's getting fulfilled to know it was fulfilled in future
      // having PaymentStatus.SUCCEEDED in payment db is not enough because that table does not get included in backup
      val preimageTry: PreimageTry = cm.getPreimageMemo.get(fullTag.paymentHash)

      cm.getPaymentInfoMemo.get(fullTag.paymentHash).toOption match {
        case None => if (preimageTry.isSuccess) becomeRevealed(preimageTry.get, adds) else becomeAborted(IncomingAborted(None), adds)
        case Some(alreadyRevealed) if alreadyRevealed.isIncoming && PaymentStatus.SUCCEEDED == alreadyRevealed.status => becomeRevealed(alreadyRevealed.preimage, adds)
        case _ if adds.exists(_.add.cltvExpiry.toLong < LNParams.blockCount.get + LNParams.cltvRejectThreshold) => becomeAborted(IncomingAborted(None), adds)
        case Some(covered) if covered.isIncoming && covered.pr.amount.isDefined && askCovered(adds, covered) => becomeRevealed(covered.preimage, adds)
        case _ => // Do nothing, wait for more parts with a timeout
      }

    case (_: ReasonableLocal, null, RECEIVING) =>
      // Just saw another related add so prolong timeout
      delayedCMDWorker.replaceWork(CMDTimeout)

    case (CMDTimeout, null, RECEIVING) =>
      become(null, FINALIZING)
      cm.stateUpdated(Nil)

    // We need this extra RECEIVING -> FINALIZING step instead of failing right away
    // in case if we ever decide to use an amount-less fast crowd-fund invoices

    case (inFlight: InFlightPayments, null, FINALIZING) =>
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      val preimageTry: PreimageTry = cm.getPreimageMemo.get(fullTag.paymentHash)

      cm.getPaymentInfoMemo.get(fullTag.paymentHash).toOption match {
        case Some(alreadyRevealed) if alreadyRevealed.isIncoming && PaymentStatus.SUCCEEDED == alreadyRevealed.status => becomeRevealed(alreadyRevealed.preimage, adds)
        case Some(coveredAll) if coveredAll.isIncoming && coveredAll.pr.amount.isDefined && askCovered(adds, coveredAll) => becomeRevealed(coveredAll.preimage, adds)
        case Some(collectedSome) if collectedSome.isIncoming && collectedSome.pr.amount.isEmpty && gotSome(adds) => becomeRevealed(collectedSome.preimage, adds)
        case _ => if (preimageTry.isSuccess) becomeRevealed(preimageTry.get, adds) else becomeAborted(IncomingAborted(PaymentTimeout.toSome), adds)
      }

    case (inFlight: InFlightPayments, revealed: IncomingRevealed, FINALIZING) =>
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      fulfill(revealed.preimage, adds)

    case (inFlight: InFlightPayments, aborted: IncomingAborted, FINALIZING) =>
      val adds = inFlight.in(fullTag).asInstanceOf[ReasonableLocals]
      abort(aborted, adds)
  }

  // Utils

  def fulfill(preimage: ByteVector32, adds: ReasonableLocals): Unit = {
    for (local <- adds) cm.sendTo(local.fulfillCommand(preimage), local.add.channelId)
  }

  def abort(data1: IncomingAborted, adds: ReasonableLocals): Unit = data1.failure match {
    case None => for (local <- adds) cm.sendTo(local.incorrectDetailsFailCommand, local.add.channelId)
    case Some(fail) => for (local <- adds) cm.sendTo(local.failCommand(fail), local.add.channelId)
  }

  def becomeAborted(data1: IncomingAborted, adds: ReasonableLocals): Unit = {
    // Fail parts and retain a failure message to maybe re-fail using the same error
    become(data1, FINALIZING)
    abort(data1, adds)
  }

  def becomeRevealed(preimage: ByteVector32, adds: ReasonableLocals): Unit = {
    // With final payment we ALREADY know a preimage, but also put it into storage
    // doing so makes it transferrable as storage db gets included in backup file
    cm.payBag.updOkIncoming(amountIn(adds), fullTag.paymentHash)
    cm.payBag.storePreimage(fullTag.paymentHash, preimage)
    cm.getPaymentInfoMemo.invalidate(fullTag.paymentHash)
    cm.getPreimageMemo.invalidate(fullTag.paymentHash)
    become(IncomingRevealed(preimage), FINALIZING)
    fulfill(preimage, adds)
  }
}

// TRAMPOLINE RELAYER

object TrampolinePaymentRelayer {
  def first(adds: ReasonableTrampolines): IncomingPacket.NodeRelayPacket = adds.head.packet
  def firstOption(adds: ReasonableTrampolines): Option[IncomingPacket.NodeRelayPacket] = adds.headOption.map(_.packet)
  def relayCovered(adds: ReasonableTrampolines): Boolean = firstOption(adds).exists(amountIn(adds) >= _.outerPayload.totalAmount)

  def amountIn(adds: ReasonableTrampolines): MilliSatoshi = adds.map(_.add.amountMsat).sum
  def expiryIn(adds: ReasonableTrampolines): CltvExpiry = adds.map(_.add.cltvExpiry).min

  def relayFee(amount: MilliSatoshi, params: TrampolineOn): MilliSatoshi = {
    val linearProportional = proportionalFee(amount, params.feeProportionalMillionths)
    trampolineFee(linearProportional.toLong, params.feeBaseMsat, params.exponent, params.logExponent)
  }

  def validateRelay(params: TrampolineOn, adds: ReasonableTrampolines, blockHeight: Long): Option[FailureMessage] =
    if (first(adds).innerPayload.invoiceFeatures.isDefined && first(adds).innerPayload.paymentSecret.isEmpty) Some(TemporaryNodeFailure) // We do not deliver to non-trampoline, non-MPP recipients
    else if (relayFee(amountIn(adds), params) > amountIn(adds) - first(adds).innerPayload.amountToForward) Some(TrampolineFeeInsufficient) // Proposed trampoline fee is less than required by our node
    else if (adds.map(_.packet.innerPayload.amountToForward).toSet.size != 1) Some(LNParams incorrectDetails first(adds).add.amountMsat) // All incoming parts must have the same amount to be forwareded
    else if (adds.map(_.packet.outerPayload.totalAmount).toSet.size != 1) Some(LNParams incorrectDetails first(adds).add.amountMsat) // All incoming parts must have the same TotalAmount value
    else if (expiryIn(adds) - first(adds).innerPayload.outgoingCltv < params.cltvExpiryDelta) Some(TrampolineExpiryTooSoon) // Proposed delta is less than required by our node
    else if (CltvExpiry(blockHeight) > first(adds).innerPayload.outgoingCltv) Some(TrampolineExpiryTooSoon) // Recepient's CLTV expiry is below current chain height
    else if (first(adds).innerPayload.amountToForward < params.minimumMsat) Some(TemporaryNodeFailure)
    else None

  def abortedWithError(failures: Failures, finalNodeId: PublicKey): TrampolineAborted = {
    val finalNodeFailure = failures.collectFirst { case remote: RemoteFailure if remote.packet.originNode == finalNodeId => remote.packet.failureMessage }
    val routingNodeFailure = failures.collectFirst { case remote: RemoteFailure if remote.packet.originNode != finalNodeId => remote.packet.failureMessage }
    val localNoRoutesFoundError = failures.collectFirst { case local: LocalFailure if local.status == PaymentFailure.NO_ROUTES_FOUND => TrampolineFeeInsufficient }
    TrampolineAborted(finalNodeFailure orElse localNoRoutesFoundError orElse routingNodeFailure getOrElse TemporaryNodeFailure)
  }
}

case class TrampolineProcessing(finalNodeId: PublicKey) extends IncomingProcessorData // SENDING
case class TrampolineStopping(retryOnceFinalized: Boolean) extends IncomingProcessorData // SENDING
case class TrampolineRevealed(preimage: ByteVector32) extends IncomingProcessorData // SENDING | FINALIZING
case class TrampolineAborted(failure: FailureMessage) extends IncomingProcessorData // FINALIZING

class TrampolinePaymentRelayer(val fullTag: FullPaymentTag, cm: ChannelMaster) extends IncomingPaymentProcessor with OutgoingPaymentMasterListener { self =>
  override def wholePaymentFailed(data: OutgoingPaymentSenderData): Unit = if (data.cmd.fullTag == fullTag) doProcess(data)
  import TrampolinePaymentRelayer._

  require(fullTag.tag == PaymentTagTlv.TRAMPLOINE_ROUTED)
  delayedCMDWorker.replaceWork(CMDTimeout)
  become(freshData = null, RECEIVING)

  cm.opm process CreatSenderFSM(fullTag)
  cm.opm.listeners += self

  def doProcess(msg: Any): Unit = (msg, data, state) match {
    case (inFlight: InFlightPayments, revealed: TrampolineRevealed, SENDING) =>
      // A special case after we have just received a first preimage and can become revealed
      val ins = inFlight.in.getOrElse(fullTag, Nil).asInstanceOf[ReasonableTrampolines]
      becomeRevealed(revealed.preimage, ins)

    case (fulfill: RemoteFulfill, _, FINALIZING | RECEIVING | SENDING) if fulfill.ourAdd.paymentHash == fullTag.paymentHash =>
      // We have outgoing in-flight payments and just got a preimage, can start fulfilling pending incoming HTLCs immediately
      become(TrampolineRevealed(fulfill.preimage), SENDING)
      cm.stateUpdated(Nil)

    case (_: OutgoingPaymentSenderData, TrampolineStopping(true), SENDING) =>
      // We were waiting for all outgoing parts to fail on app restart, try again
      become(null, RECEIVING)
      cm.stateUpdated(Nil)

    case (data: OutgoingPaymentSenderData, _: TrampolineStopping, SENDING) =>
      // We were waiting for all outgoing parts to fail on app restart, fail incoming
      become(abortedWithError(data.failures, invalidPubKey), FINALIZING)
      cm.stateUpdated(Nil)

    case (data: OutgoingPaymentSenderData, processing: TrampolineProcessing, SENDING) =>
      // This was a normal operation where we were trying to deliver a payment to recipient
      become(abortedWithError(data.failures, processing.finalNodeId), FINALIZING)
      cm.stateUpdated(Nil)

    case (inFlight: InFlightPayments, _, FINALIZING | SENDING) if !inFlight.allTags.contains(fullTag) =>
      // This happens AFTER we have resolved all outgoing payments and started resolving related incoming payments
      becomeShutdown

    case (inFlight: InFlightPayments, null, RECEIVING) =>
      // We have either just seen another part or restored an app with parts
      val preimageTry: PreimageTry = cm.getPreimageMemo.get(fullTag.paymentHash)
      val ins = inFlight.in.getOrElse(fullTag, Nil).asInstanceOf[ReasonableTrampolines]
      val outs: OutgoingAdds = inFlight.out.getOrElse(fullTag, Nil)

      preimageTry match {
        case Success(preimage) => becomeRevealed(preimage, ins)
        case _ if relayCovered(ins) && outs.isEmpty => becomeSendingOrAborted(ins)
        case _ if relayCovered(ins) && outs.nonEmpty => become(TrampolineStopping(retryOnceFinalized = true), SENDING) // App has been restarted midway, fail safely and retry
        case _ if outs.nonEmpty => become(TrampolineStopping(retryOnceFinalized = false), SENDING) // Have not collected enough yet have outgoing (this is pathologic state)
        case _ if !inFlight.allTags.contains(fullTag) => becomeShutdown // Somehow no leftovers are present at all, nothing left to do
        case _ => // Do nothing, wait for more parts with a timeout
      }

    case (_: ReasonableTrampoline, null, RECEIVING) =>
      // Just saw another related add so prolong timeout
      delayedCMDWorker.replaceWork(CMDTimeout)

    case (CMDTimeout, null, RECEIVING) =>
      // Sender must not have outgoing payments in this state
      become(TrampolineAborted(PaymentTimeout), FINALIZING)
      cm.stateUpdated(Nil)

    case (inFlight: InFlightPayments, revealed: TrampolineRevealed, FINALIZING) =>
      val ins = inFlight.in.getOrElse(fullTag, Nil).asInstanceOf[ReasonableTrampolines]
      fulfill(revealed.preimage, ins)

    case (inFlight: InFlightPayments, aborted: TrampolineAborted, FINALIZING) =>
      val ins = inFlight.in.getOrElse(fullTag, Nil).asInstanceOf[ReasonableTrampolines]
      abort(aborted, ins)
  }

  def fulfill(preimage: ByteVector32, adds: ReasonableTrampolines): Unit = {
    for (local <- adds) cm.sendTo(local.fulfillCommand(preimage), local.add.channelId)
  }

  def abort(data1: TrampolineAborted, adds: ReasonableTrampolines): Unit = {
    for (local <- adds) cm.sendTo(local.failCommand(data1.failure), local.add.channelId)
  }

  def becomeSendingOrAborted(adds: ReasonableTrampolines): Unit = {
    require(adds.nonEmpty, "A set of incoming HTLCs must be non-empty")
    val result = validateRelay(LNParams.trampoline, adds, LNParams.blockCount.get)

    result match {
      case Some(failure) =>
        val data1 = TrampolineAborted(failure)
        become(data1, FINALIZING)
        abort(data1, adds)

      case None =>
        val innerPayload = first(adds).innerPayload
        val totalFeeReserve = amountIn(adds) - innerPayload.amountToForward - relayFee(amountIn(adds), LNParams.trampoline)
        val routerConf = LNParams.routerConf.copy(maxCltv = expiryIn(adds) - innerPayload.outgoingCltv - LNParams.trampoline.cltvExpiryDelta)
        val extraEdges = RouteCalculation.makeExtraEdges(innerPayload.invoiceRoutingInfo.map(_.map(_.toList).toList).getOrElse(Nil), innerPayload.outgoingNodeId)

        val send = SendMultiPart(fullTag, routerConf, innerPayload.outgoingNodeId,
          onionTotal = innerPayload.amountToForward, actualTotal = innerPayload.amountToForward,
          totalFeeReserve, innerPayload.outgoingCltv, allowedChans = cm.all.values.toSeq)

        become(TrampolineProcessing(innerPayload.outgoingNodeId), SENDING)
        // If invoice features are present, the sender is asking us to relay to a non-trampoline recipient, it is known that recipient supports MPP
        if (innerPayload.invoiceFeatures.isDefined) cm.opm process send.copy(assistedEdges = extraEdges, paymentSecret = innerPayload.paymentSecret.get)
        else cm.opm process send.copy(onionTlvs = OnionTlv.TrampolineOnion(adds.head.packet.nextPacket) :: Nil, paymentSecret = randomBytes32)
    }
  }

  def becomeRevealed(preimage: ByteVector32, adds: ReasonableTrampolines): Unit = {
    // We might not have enough or no incoming payments at all in pathological states
    become(TrampolineRevealed(preimage), FINALIZING)
    fulfill(preimage, adds)

    for {
      packet <- firstOption(adds)
      sender <- cm.opm.data.payments.get(fullTag)
      finalFee = packet.outerPayload.totalAmount - packet.innerPayload.amountToForward - sender.data.usedFee
    } cm.payBag.addRelayedPreimageInfo(fullTag.paymentHash, preimage, packet.innerPayload.amountToForward, finalFee)
  }

  def becomeShutdown: Unit = {
    cm.opm process RemoveSenderFSM(fullTag)
    cm.inProcessors -= fullTag
    cm.opm.listeners -= self
    become(null, SHUTDOWN)
  }
}