package com.circaso.feature.contacts.domain

class ObserveContactsUseCase(private val repository: ContactsRepository) { operator fun invoke() = repository.contacts }
class ObserveBlocksUseCase(private val repository: ContactsRepository) { operator fun invoke() = repository.blocks }
class RefreshContactsUseCase(private val repository: ContactsRepository) { suspend operator fun invoke() = repository.refreshContacts() }
class RefreshBlocksUseCase(private val repository: ContactsRepository) { suspend operator fun invoke() = repository.refreshBlocks() }
class SyncPhoneBookUseCase(private val repository: ContactsRepository) { suspend operator fun invoke(phones: List<String>) = repository.syncPhoneBook(phones) }
class ConnectUseCase(private val repository: ContactsRepository) { suspend operator fun invoke(via: ConnectVia, value: String) = repository.connect(via, value) }
class CreateInviteUseCase(private val repository: ContactsRepository) { suspend operator fun invoke() = repository.createInvite() }
class RevokeInviteUseCase(private val repository: ContactsRepository) { suspend operator fun invoke(token: String) = repository.revokeInvite(token) }
class RemoveContactUseCase(private val repository: ContactsRepository) { suspend operator fun invoke(userId: String) = repository.removeContact(userId) }
class BlockUseCase(private val repository: ContactsRepository) { suspend operator fun invoke(person: Person) = repository.block(person) }
class UnblockUseCase(private val repository: ContactsRepository) { suspend operator fun invoke(userId: String) = repository.unblock(userId) }
class CanReachUseCase(private val repository: ContactsRepository) { suspend operator fun invoke(userId: String) = repository.canReach(userId) }
