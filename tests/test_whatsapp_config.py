from core.config import GREENAPI_MONITOR_CHAT_ID, GREENAPI_REPORT_CHAT_ID


def test_separate_chat_ids_for_monitoring_and_reports():
    assert GREENAPI_MONITOR_CHAT_ID != ""
    assert GREENAPI_REPORT_CHAT_ID != ""
    assert GREENAPI_MONITOR_CHAT_ID != GREENAPI_REPORT_CHAT_ID
