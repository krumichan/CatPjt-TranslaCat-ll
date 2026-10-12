package jp.co.translacat.languagelearning.support

/** 최신 스키마 기대값이다. 특정 과거 버전을 고정한 검사에는 사용하지 않는다. */
internal object CurrentSchema {
    const val VERSION = 30

    // 병렬 작업에서 예약한 V012는 사용하지 않았다. 버전 번호와 실제 migration 수를 구분한다.
    const val MIGRATION_COUNT = 29
    // V023은 후보 생성 테이블 하나를 추가하며 V024~V030는 기존 정책 제약만 변경한다.
    const val TABLE_COUNT = 64L
}
