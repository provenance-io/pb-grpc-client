package io.provenance.client.wallet

import io.provenance.client.Signer
import io.provenance.hdwallet.bip39.MnemonicWords
import io.provenance.hdwallet.common.hashing.sha256
import io.provenance.hdwallet.signer.BCECSigner
import io.provenance.hdwallet.wallet.Account
import io.provenance.hdwallet.wallet.Wallet

enum class NetworkType(
    /**
     * The hrp (Human Readable Prefix) of the network address
     */
    val prefix: String,
    /**
     * The HD wallet path
     */
    val path: String
) {
    TESTNET("tp", "m/44'/1'/0'/0/0'"),
    MAINNET("pb", "m/505'/1'/0'/0/0")
}

class WalletSigner(networkType: NetworkType, mnemonic: String, passphrase: String = "") : Signer {

    val wallet = Wallet.fromMnemonic(networkType.prefix, passphrase.toCharArray(), MnemonicWords.of(mnemonic))

    val account : Account = wallet[networkType.path]

    override fun address(): String = account.address

    override fun sign(data: ByteArray): ByteArray = BCECSigner()
        .sign(account.keyPair.privateKey, data.sha256())
        .encodeAsBTC()
}