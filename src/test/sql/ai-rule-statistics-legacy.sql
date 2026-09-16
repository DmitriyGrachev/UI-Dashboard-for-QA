-- Frozen pre-optimization query for the fixed scale fixture and its three ordered rules.
WITH recoverable AS (SELECT image_id FROM ai_review_task WHERE status='PROCESSING' AND lease_expires_at<='2026-09-16T10:00:00Z' ORDER BY lease_expires_at,image_id LIMIT 100), remaining AS (SELECT CASE WHEN TRUE AND ai.game_code='bj_igt' AND ai.token_id=1 THEN '00000000-0000-0000-0000-000000000001'::uuid WHEN TRUE AND ai.game_code='bj_igt' THEN '00000000-0000-0000-0000-000000000002'::uuid WHEN TRUE AND ai.game_code='bj_single_deck_ags' THEN '00000000-0000-0000-0000-000000000003'::uuid ELSE NULL::uuid END AS rule_id FROM ai_review_task ai JOIN image_asset ia ON ia.id=ai.image_id
WHERE ((ai.status='PENDING' AND (ai.retry_after IS NULL OR ai.retry_after<='2026-09-16T10:00:00Z')) OR ai.image_id IN (SELECT image_id FROM recoverable))
AND (ai.file_available OR ai.cloud_available_at IS NOT NULL)
 AND (ai.file_available OR ai.cloud_available_at > '2026-08-26T10:00:00Z')
 AND (ia.file_available OR (NULLIF(BTRIM(ia.cloud_object_key),'') IS NOT NULL AND ia.cloud_uploaded_at > '2026-08-26T10:00:00Z'))), counts AS (
SELECT rule_id,COUNT(*) AS remaining,0::bigint AS processing,0::bigint AS completed,0::bigint AS failed FROM remaining WHERE rule_id IS NOT NULL GROUP BY rule_id
UNION ALL
SELECT issued_rule_id,0,COUNT(*) FILTER(WHERE status='PROCESSING'),COUNT(*) FILTER(WHERE status='COMPLETED'),COUNT(*) FILTER(WHERE status='FAILED')
FROM ai_review_task WHERE issued_rule_id IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000003')
AND status IN ('PROCESSING','COMPLETED','FAILED') GROUP BY issued_rule_id)
SELECT rule_id,SUM(remaining) AS remaining,SUM(processing) AS processing,SUM(completed) AS completed,SUM(failed) AS failed FROM counts GROUP BY rule_id
