package io.provenance.client.grpc

import com.google.protobuf.ByteString
import cosmos.auth.v1beta1.Auth
import cosmos.tx.signing.v1beta1.Signing.SignMode
import cosmos.tx.v1beta1.TxOuterClass.*
import cosmos.tx.v1beta1.TxOuterClass.ModeInfo.Single
import io.provenance.client.grpc.GasEstimate
import com.google.protobuf.Any
import cosmos.crypto.secp256k1.Keys



interface Signer {
    fun address(): String
    fun pubKey(): Keys.PubKey
    fun sign(data: ByteArray): ByteArray
}

data class BaseReqSigner(
        val signer: Signer,
        val sequenceOffset: Int = 0,
        val account: Auth.BaseAccount? = null
)

data class BaseReq(
    val signers: List<BaseReqSigner>,
    val body: TxBody,
    val chainId: String,
    val gasAdjustment: Double? = null
) {

    fun buildAuthInfo(gasEstimate: GasEstimate = GasEstimate(0)): AuthInfo =
        AuthInfo.newBuilder()
            .setFee(
                Fee.newBuilder()
                    .addAllAmount(
                            gasEstimate.feeCalculated
                    )
                    .setGasLimit(gasEstimate.limit)
            )
            .addAllSignerInfos(
                signers.map {
                    SignerInfo.newBuilder()
                        .setPublicKey(Any.pack(it.signer.pubKey(), ""))
                        .setModeInfo(
                            ModeInfo.newBuilder()
                                .setSingle(Single.newBuilder().setModeValue(SignMode.SIGN_MODE_DIRECT_VALUE))
                        )
                        .setSequence(it.account!!.sequence + it.sequenceOffset)
                        .build()
                }
            )
            .build()

    fun buildSignDocBytesList(authInfoBytes: ByteString, bodyBytes: ByteString): List<ByteArray> =
        signers.map {
            SignDoc.newBuilder()
                .setBodyBytes(bodyBytes)
                .setAuthInfoBytes(authInfoBytes)
                .setChainId(chainId)
                .setAccountNumber(it.account!!.accountNumber)
                .build()
                .toByteArray()
        }
}
