-- LL 카탈로그 이관 후 필요할 때만 실행하는 영어 표시명 보충 자료다.
-- 원본: BE phase2_language_learning_speaking_seed.sql의 38개 어휘와 공용 주제 10개.
-- 기존 표시명·선택·사용자 자료는 변경하지 않고 en-US가 없는 공용 키워드만 추가한다.
-- 신규 DB에 카탈로그가 아직 없으면 삽입하지 않는다. 애플리케이션도 같은 고정 자료로 표시한다.
INSERT INTO language_learning_system_keyword_locale (system_keyword_id, locale, display_name)
SELECT keyword_row.id, 'en-US', label_row.display_name
FROM language_learning_system_keyword keyword_row
JOIN (
    SELECT 'DAILY' AS canonical_key, 'Daily conversation' AS display_name
    UNION ALL SELECT 'TRAVEL', 'Travel'
    UNION ALL SELECT 'FOOD', 'Food'
    UNION ALL SELECT 'SHOPPING', 'Shopping'
    UNION ALL SELECT 'BUSINESS', 'Business'
    UNION ALL SELECT 'IT', 'IT'
    UNION ALL SELECT 'HOBBY', 'Hobbies'
    UNION ALL SELECT 'GAME', 'Games'
    UNION ALL SELECT 'CULTURE', 'Culture'
    UNION ALL SELECT 'FREE_TALK', 'Free talk'
    UNION ALL SELECT 'DAILY_FAMILY', 'Family'
    UNION ALL SELECT 'DAILY_FRIEND', 'Friends'
    UNION ALL SELECT 'DAILY_WEATHER', 'Weather'
    UNION ALL SELECT 'DAILY_HOUSEWORK', 'Housework'
    UNION ALL SELECT 'TRAVEL_AIRPORT', 'Airport'
    UNION ALL SELECT 'TRAVEL_HOTEL', 'Hotel'
    UNION ALL SELECT 'TRAVEL_DIRECTIONS', 'Directions'
    UNION ALL SELECT 'TRAVEL_TRANSPORT', 'Transportation'
    UNION ALL SELECT 'TRAVEL_SIGHTSEEING', 'Sightseeing'
    UNION ALL SELECT 'FOOD_RESTAURANT', 'Restaurant'
    UNION ALL SELECT 'FOOD_CAFE', 'Cafe'
    UNION ALL SELECT 'FOOD_COOKING', 'Cooking'
    UNION ALL SELECT 'FOOD_FOOD_ORDER', 'Ordering food'
    UNION ALL SELECT 'SHOPPING_PRICE', 'Price'
    UNION ALL SELECT 'SHOPPING_PAYMENT', 'Payment'
    UNION ALL SELECT 'SHOPPING_EXCHANGE', 'Exchange'
    UNION ALL SELECT 'SHOPPING_DELIVERY', 'Delivery'
    UNION ALL SELECT 'BUSINESS_MEETING', 'Meeting'
    UNION ALL SELECT 'BUSINESS_SCHEDULE', 'Schedule'
    UNION ALL SELECT 'BUSINESS_CUSTOMER_SERVICE', 'Customer service'
    UNION ALL SELECT 'BUSINESS_PRESENTATION', 'Presentation'
    UNION ALL SELECT 'IT_DEVELOPMENT', 'Development'
    UNION ALL SELECT 'IT_API', 'API'
    UNION ALL SELECT 'IT_DATABASE', 'Database'
    UNION ALL SELECT 'IT_DEPLOYMENT', 'Deployment'
    UNION ALL SELECT 'IT_INCIDENT_RESPONSE', 'Incident response'
    UNION ALL SELECT 'HOBBY_MOVIE', 'Movies'
    UNION ALL SELECT 'HOBBY_MUSIC', 'Music'
    UNION ALL SELECT 'HOBBY_READING', 'Reading'
    UNION ALL SELECT 'HOBBY_EXERCISE', 'Exercise'
    UNION ALL SELECT 'GAME_COOPERATION', 'Cooperation'
    UNION ALL SELECT 'GAME_STRATEGY', 'Strategy'
    UNION ALL SELECT 'GAME_CHARACTER', 'Character'
    UNION ALL SELECT 'GAME_ONLINE_GAME', 'Online games'
    UNION ALL SELECT 'CULTURE_FESTIVAL', 'Festival'
    UNION ALL SELECT 'CULTURE_ETIQUETTE', 'Etiquette'
    UNION ALL SELECT 'CULTURE_LOCAL_CULTURE', 'Local culture'
    UNION ALL SELECT 'CULTURE_LANGUAGE_DIFFERENCE', 'Language differences'
) label_row ON UPPER(keyword_row.canonical_key) = label_row.canonical_key
WHERE NOT EXISTS (
    SELECT 1 FROM language_learning_system_keyword_locale existing
    WHERE existing.system_keyword_id = keyword_row.id AND existing.locale = 'en-US'
);
