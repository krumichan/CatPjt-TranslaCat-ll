package jp.co.translacat.languagelearning.support

/** 최신 스키마 기대값이다. 특정 과거 버전을 고정한 검사에는 사용하지 않는다. */
internal object CurrentSchema {
    const val VERSION = 36

    // 병렬 작업에서 예약한 V012는 사용하지 않았다. 버전 번호와 실제 migration 수를 구분한다.
    const val MIGRATION_COUNT = 35
    // V033은 snapshot 테이블을 추가했고 V034~V036은 기존 코칭 정책 CHECK만 확장한다.
    const val TABLE_COUNT = 65L
}
