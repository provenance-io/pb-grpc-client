package io.provenance.client.coroutines

import com.google.protobuf.ByteString
import cosmos.auth.v1beta1.QueryGrpcKt
import cosmos.auth.v1beta1.QueryOuterClass
import cosmos.tx.v1beta1.ServiceOuterClass
import cosmos.tx.v1beta1.TxOuterClass
import cosmos.tx.v1beta1.TxOuterClass.TxBody
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.provenance.client.grpc.BaseReq
import io.provenance.client.grpc.BaseReqSigner
import io.provenance.client.grpc.GasEstimate
import java.io.Closeable
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class ChannelOpts(
    val inboundMessageSize: Int = 40 * 1024 * 1024, // ~ 20 MB
    val idleTimeout: Duration = 5.minutes,
    val keepAliveTime: Duration = 60.seconds, // ~ 12 pbc block cuts
    val keepAliveTimeout: Duration = 20.seconds,
    val executor: Executor = Executors.newFixedThreadPool(8)
)

open class PbCoroutinesClient(
    val chainId: String,
    val channelUri: URI,
    val gasEstimationMethod: PbGasEstimator,
    opts: ChannelOpts = ChannelOpts(),
    channelConfigLambda: (NettyChannelBuilder) -> Unit = { }
) : Closeable {

    companion object {
        private val SECURE_URL_SCHEMES = listOf("https", "grpcs", "tcp+tls")
    }

    private val channel = NettyChannelBuilder.forAddress(channelUri.host, channelUri.port)
        .apply {
            if (channelUri.scheme in SECURE_URL_SCHEMES) {
                useTransportSecurity()
            } else {
                usePlaintext()
            }
        }
        .executor(opts.executor)
        .maxInboundMessageSize(opts.inboundMessageSize)
        .idleTimeout(opts.idleTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .keepAliveTime(opts.keepAliveTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .keepAliveTimeout(opts.keepAliveTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .also { builder -> channelConfigLambda(builder) }
        .build()

    override fun close() {
        channel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
    }

    // Service clients
    val cosmosService = cosmos.tx.v1beta1.ServiceGrpcKt.ServiceCoroutineStub(channel)
    val tendermintService = cosmos.base.tendermint.v1beta1.ServiceGrpcKt.ServiceCoroutineStub(channel)

    // Kotlin things.
    val authClient = cosmos.auth.v1beta1.QueryGrpcKt.QueryCoroutineStub(channel)
    val attributesClient = io.provenance.attribute.v1.QueryGrpcKt.QueryCoroutineStub(channel)
    val authzClient = cosmos.authz.v1beta1.QueryGrpcKt.QueryCoroutineStub(channel)
    val bankClient = cosmos.bank.v1beta1.QueryGrpcKt.QueryCoroutineStub(channel)
    val channelClient = ibc.core.channel.v1.QueryGrpcKt.QueryCoroutineStub(channel)
    val clientClient = ibc.core.client.v1.QueryGrpcKt.QueryCoroutineStub(channel)
    val connectionClient = ibc.core.connection.v1.QueryGrpcKt.QueryCoroutineStub(channel)
    val distributionClient = cosmos.distribution.v1beta1.QueryGrpcKt.QueryCoroutineStub(channel)
    val evidenceClient = cosmos.evidence.v1beta1.QueryGrpcKt.QueryCoroutineStub(channel)
    val feegrantClient = cosmos.feegrant.v1beta1.QueryGrpcKt.QueryCoroutineStub(channel)
    val govClient = cosmos.gov.v1beta1.QueryGrpcKt.QueryCoroutineStub(channel)
    val markerClient = io.provenance.marker.v1.QueryGrpcKt.QueryCoroutineStub(channel)
    val metadataClient = io.provenance.metadata.v1.QueryGrpcKt.QueryCoroutineStub(channel)
    val mintClient = cosmos.mint.v1beta1.QueryGrpcKt.QueryCoroutineStub(channel)
    val msgFeeClient = io.provenance.msgfees.v1.QueryGrpcKt.QueryCoroutineStub(channel)
    val nameClient = io.provenance.name.v1.QueryGrpcKt.QueryCoroutineStub(channel)
    val paramsClient = cosmos.params.v1beta1.QueryGrpcKt.QueryCoroutineStub(channel)
    val slashingClient = cosmos.slashing.v1beta1.QueryGrpcKt.QueryCoroutineStub(channel)
    val stakingClient = cosmos.staking.v1beta1.QueryGrpcKt.QueryCoroutineStub(channel)
    val transferClient = ibc.applications.transfer.v1.QueryGrpcKt.QueryCoroutineStub(channel)
    val upgradeClient = cosmos.upgrade.v1beta1.QueryGrpcKt.QueryCoroutineStub(channel)
    val wasmClient = cosmwasm.wasm.v1.QueryGrpcKt.QueryCoroutineStub(channel)

    suspend fun baseRequest(
        txBody: TxBody,
        signers: List<BaseReqSigner>,
        gasAdjustment: Double? = null,
        feeGranter: String? = null,
    ): BaseReq =
        signers.map {
            BaseReqSigner(
                signer = it.signer,
                sequenceOffset = it.sequenceOffset,
                account = it.account ?: this.authClient.getBaseAccount(it.signer.address())
            )
        }.let {
            BaseReq(
                signers = it,
                body = txBody,
                chainId = chainId,
                gasAdjustment = gasAdjustment,
                feeGranter = feeGranter
            )
        }

    private fun mkTx(body: TxOuterClass.Tx.Builder.() -> Unit): TxOuterClass.Tx =
        TxOuterClass.Tx.newBuilder().also(body).build()

    suspend fun estimateTx(baseReq: BaseReq): GasEstimate {
        val tx = mkTx {
            body = baseReq.body
            authInfo = baseReq.buildAuthInfo()
        }

        return baseReq.buildSignDocBytesList(tx.authInfo.toByteString(), tx.body.toByteString())
            .mapIndexed { index, signDocBytes ->
                baseReq.signers[index].signer.sign(signDocBytes).let { ByteString.copyFrom(it) }
            }.let { signatures ->
                val signedTx = tx.toBuilder().addAllSignatures(signatures).build()
                val gasAdjustment = baseReq.gasAdjustment ?: GasEstimate.DEFAULT_FEE_ADJUSTMENT
                val gasEstimator = gasEstimationMethod(this)
                gasEstimator(signedTx, gasAdjustment)
            }
    }

    private fun buildTx(
        baseReq: BaseReq,
        gasEstimate: GasEstimate,
    ): TxOuterClass.TxRaw {
        val authInfoBytes = baseReq.buildAuthInfo(gasEstimate).toByteString()
        val txBodyBytes = baseReq.body.toByteString()

        val signedTxs = baseReq.buildSignDocBytesList(authInfoBytes, txBodyBytes).mapIndexed { index, signDocBytes ->
            baseReq.signers[index].signer.sign(signDocBytes).let { ByteString.copyFrom(it) }
        }

        return TxOuterClass.TxRaw.newBuilder()
            .setAuthInfoBytes(authInfoBytes)
            .setBodyBytes(txBodyBytes)
            .addAllSignatures(signedTxs)
            .build()
    }

    suspend fun broadcastTx(
        baseReq: BaseReq,
        gasEstimate: GasEstimate,
        mode: ServiceOuterClass.BroadcastMode = ServiceOuterClass.BroadcastMode.BROADCAST_MODE_SYNC
    ): ServiceOuterClass.BroadcastTxResponse {
        return cosmosService.broadcastTx(
            ServiceOuterClass.BroadcastTxRequest
                .newBuilder()
                .setTxBytes(buildTx(baseReq, gasEstimate).toByteString())
                .setMode(mode)
                .build()
        )
    }

    suspend fun estimateAndBroadcastTx(
        txBody: TxBody,
        signers: List<BaseReqSigner>,
        mode: ServiceOuterClass.BroadcastMode = ServiceOuterClass.BroadcastMode.BROADCAST_MODE_SYNC,
        gasAdjustment: Double? = null,
        feeGranter: String? = null
    ): ServiceOuterClass.BroadcastTxResponse = baseRequest(
        txBody = txBody,
        signers = signers,
        gasAdjustment = gasAdjustment,
        feeGranter = feeGranter
    ).let { baseReq -> broadcastTx(baseReq, estimateTx(baseReq), mode) }
}

/**
 * Given an address, get the base account associated with it.
 *
 * See [Accounts](https://github.com/FigureTechnologies/service-wallet/blob/v45/pb-client/src/main/kotlin/com/figure/wallet/pbclient/client/grpc/Accounts.kt#L18).
 *
 * @param bech32Address The bech32 address to fetch.
 * @return [cosmos.auth.v1beta1.Auth.BaseAccount] or throw [IllegalArgumentException] if the account type is not supported.
 */
suspend fun QueryGrpcKt.QueryCoroutineStub.getBaseAccount(bech32Address: String): cosmos.auth.v1beta1.Auth.BaseAccount =
    account(QueryOuterClass.QueryAccountRequest.newBuilder().setAddress(bech32Address).build()).account.run {
        when {
            this.`is`(cosmos.auth.v1beta1.Auth.BaseAccount::class.java) -> unpack(cosmos.auth.v1beta1.Auth.BaseAccount::class.java)
            else -> throw IllegalArgumentException("Account type not handled:$typeUrl")
        }
    }

/**
 * Unused
 */
suspend fun <T> suspendable(block: () -> T): T {
    return suspendCoroutine { continuation ->
        thread {
            try {
                val result = block()
                continuation.resume(result)
            } catch (e: Throwable) {
                continuation.resumeWithException(e)
            }
        }
    }
}

/**
 * Unused
 */
suspend fun <T> Future<T>.suspendable(timeout: Duration = Duration.ZERO): T {
    return suspendable {
        if (timeout == Duration.ZERO) get()
        else get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }
}