package jp.co.translacat.languagelearning.shared.api

import jp.co.translacat.languagelearning.shared.identity.InvalidLearningPublicId
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LearningPublicIdTest {
    @Test
    fun `현재 공개 ID의 부호와 DB 식별자 변환을 검증한다`() {
        // 준비
        val storedId = 17L

        // 실행
        val publicId = LearningPublicId.encode(storedId)

        // 검증
        assertEquals(-17L, publicId)
        assertEquals(storedId, LearningPublicId.decode(publicId.toString()))
        assertFailsWith<InvalidLearningPublicId> { LearningPublicId.decode("17") }
    }

    @Test
    fun `브라우저에서 정밀도를 잃는 ID와 영은 거부한다`() {
        // 준비
        val invalidIds = listOf(null, "0", "-9007199254740992", Long.MIN_VALUE.toString(), "invalid")

        // 실행 및 검증
        invalidIds.forEach { value ->
            assertFailsWith<InvalidLearningPublicId> { LearningPublicId.decode(value) }
        }
        assertFailsWith<IllegalArgumentException> { LearningPublicId.encode(9_007_199_254_740_992L) }
    }
}
