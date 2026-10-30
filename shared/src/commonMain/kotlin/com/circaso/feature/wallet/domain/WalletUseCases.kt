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
