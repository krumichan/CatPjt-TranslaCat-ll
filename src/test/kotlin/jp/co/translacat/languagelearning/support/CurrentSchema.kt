package jp.co.translacat.languagelearning.support

/** 최신 스키마 기대값이다. 특정 과거 버전을 고정한 검사에는 사용하지 않는다. */
internal object CurrentSchema {
    const val VERSION = 38

    // 병렬 작업에서 예약한 V012는 사용하지 않았다. 버전 번호와 실제 migration 수를 구분한다.
    const val MIGRATION_COUNT = 37
    // V038은 Web AI 계획·실행·결과·문항 예약 테이블 네 개를 추가한다.
    const val TABLE_COUNT = 69L
}
