package jp.co.translacat.languagelearning.features.leveltest.api.dto

import kotlinx.serialization.Serializable

/** 得点や所有者をクライアントから受け取らず、選択した帯域だけを指定する。 */
@Serializable
internal data class LevelSelfSelectRequestDto(val band: String? = null, val idempotencyKey: String? = null)
