package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.accountdeletion.application.AccountAudioWrites

/** 테스트 fixture는 기존 포트를 유지하고 운영 DI에서만 영속 owner/업로드 경계를 연결한다. */
internal suspend fun SpeakingAudioStore.putOwned(
    user: Long, key: String, bytes: ByteArray, type: String, writes: AccountAudioWrites?,
) {
    if (writes == null) put(key, bytes, type)
    else writes.write(user, "SPEAKING", key) { put(key, bytes, type) }
}
