package com.circaso.feature.profile.domain

class ObserveProfileUseCase(private val repository: ProfileRepository) { operator fun invoke() = repository.profile }
class RefreshProfileUseCase(private val repository: ProfileRepository) { suspend operator fun invoke() = repository.refresh() }
class UpdateProfileUseCase(private val repository: ProfileRepository) { suspend operator fun invoke(change: ProfileChange) = repository.update(change) }
class CheckHandleUseCase(private val repository: ProfileRepository) { suspend operator fun invoke(handle: String) = repository.checkHandle(handle) }
class ClaimHandleUseCase(private val repository: ProfileRepository) { suspend operator fun invoke(handle: String) = repository.claimHandle(handle) }
class UploadAvatarUseCase(private val repository: ProfileRepository) { suspend operator fun invoke(image: AvatarImage) = repository.uploadAvatar(image) }
class RemoveAvatarUseCase(private val repository: ProfileRepository) { suspend operator fun invoke() = repository.removeAvatar() }
class FindByHandleUseCase(private val repository: ProfileRepository) { suspend operator fun invoke(handle: String) = repository.findByHandle(handle) }
class GetPublicProfileUseCase(private val repository: ProfileRepository) { suspend operator fun invoke(userId: String) = repository.publicProfile(userId) }
