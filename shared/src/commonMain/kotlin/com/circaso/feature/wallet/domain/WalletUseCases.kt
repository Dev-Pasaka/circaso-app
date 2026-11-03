package com.circaso.feature.wallet.domain

class ObserveWalletUseCase(private val wallet: WalletRepository) { operator fun invoke() = wallet.wallet }
class RefreshWalletUseCase(private val wallet: WalletRepository) { suspend operator fun invoke() = wallet.refresh() }
class CreateWalletUseCase(private val wallet: WalletRepository) { suspend operator fun invoke() = wallet.create() }
class ObserveTransfersUseCase(private val wallet: WalletRepository) { operator fun invoke() = wallet.transfers }
class ObserveTransferUseCase(private val wallet: WalletRepository) { operator fun invoke(transferId: String) = wallet.transfer(transferId) }
class LoadTransferUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(transferId: String) = wallet.loadTransfer(transferId) }
class GetChainsUseCase(private val wallet: WalletRepository) { suspend operator fun invoke() = wallet.chains() }
class AddMissingChainsUseCase(private val wallet: WalletRepository) { suspend operator fun invoke() = wallet.addMissingChains() }
class AddChainUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(blockchain: String) = wallet.addChain(blockchain) }
class CheckRecipientUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(userId: String, blockchain: String?, chatId: String? = null) = wallet.recipient(userId, blockchain, chatId) }
class RemindRecipientUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(userId: String) = wallet.remind(userId) }
class ValidateAddressUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(blockchain: String, address: String) = wallet.validateAddress(blockchain, address) }

/** Online-only: the person signs in the Circle SDK. One [idempotencyKey] per payment, kept by the screen across retries. */
class PayUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(request: PaymentRequest, idempotencyKey: String) = wallet.pay(request, idempotencyKey) }

// ---- Group wallets (phase 2). Reads come from the phone's copy; every write is online-only and never queued.

/** Read. */
class ObserveWalletsUseCase(private val wallet: WalletRepository) { operator fun invoke() = wallet.wallets }
class RefreshWalletsUseCase(private val wallet: WalletRepository) { suspend operator fun invoke() = wallet.refreshWallets() }
/** Read. [walletId] null is the person's own. */
class ObserveWalletOfUseCase(private val wallet: WalletRepository) { operator fun invoke(walletId: String?) = wallet.walletOf(walletId) }
class RefreshWalletOfUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(walletId: String?) = wallet.refreshWallet(walletId) }
/** Read. */
class ObserveWalletActivityUseCase(private val wallet: WalletRepository) { operator fun invoke(walletId: String) = wallet.activity(walletId) }
class RefreshWalletActivityUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(walletId: String) = wallet.refreshActivity(walletId) }
/** Online-only (W15). */
class GetGroupWalletUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(chatId: String) = wallet.groupWallet(chatId) }
/** Online-only: W14 and the group wallet's PIN in the Circle SDK. */
class StartGroupWalletUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(chatId: String, name: String) = wallet.startGroupWallet(chatId, name) }
class AwaitGroupWalletUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(chatId: String) = wallet.awaitGroupWallet(chatId) }
/** Online-only: one wallet's fingerprint / Face ID. */
class EnableWalletBiometricsUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(subject: SignSubject) = wallet.enableBiometricApproval(subject) }
/** Online-only: W13 for one wallet. */
class AddWalletNetworksUseCase(private val wallet: WalletRepository) { suspend operator fun invoke(subject: SignSubject) = wallet.addMissingChains(subject) }
