import json
from typing import Any, Dict, List, Optional

from core.api_client import APIClient
from core.config import ZAPIZI_BASE_URL, ZAPIZI_ORGANIZATION_ID


class ZapiziClient(APIClient):
    def __init__(self, timeout: int = 30):
        super().__init__(ZAPIZI_BASE_URL, timeout=timeout)
        self.last_token: Optional[str] = None
        self.last_refresh_token: Optional[str] = None

    @staticmethod
    def default_headers(
        token: Optional[str] = None,
        refresh_token: Optional[str] = None,
        organization_id: Optional[int] = None,
        context_type: str = "organization",
        context_id: Optional[int] = None,
    ) -> Dict[str, str]:
        if organization_id is None:
            organization_id = ZAPIZI_ORGANIZATION_ID
        headers = {
            "accept": "application/json, text/plain, */*",
            "accept-language": "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "authorization": f"Bearer {token}" if token else "",
            "content-type": "application/json",
            "user-agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            "sec-fetch-dest": "empty",
            "sec-fetch-mode": "cors",
            "sec-fetch-site": "same-origin",
            "referer": "https://portal.zapizi.com.br/dashboard/machine/list",
            "sec-ch-ua": '"Not;A=Brand";v="8", "Chromium";v="150", "Google Chrome";v="150"',
            "sec-ch-ua-mobile": "?0",
            "sec-ch-ua-platform": '"macOS"',
            "priority": "u=1, i",
        }
        if not token:
            headers.pop("authorization", None)

        if refresh_token:
            headers["x-refresh-token"] = refresh_token

        cookie_parts = [
            "pys_start_session=true",
            "pys_first_visit=true",
            "pysTrafficSource=google.com",
            "pys_landing_page=https://zap.zapizi.com.br/zapizi-home/",
            "last_pysTrafficSource=google.com",
            "last_pys_landing_page=https://zap.zapizi.com.br/zapizi-home/",
            "_fbp=fb.1.1785517238728.5950198252",
        ]
        if organization_id is not None:
            selected_context = (
                '{"contextType":"organization","contextId":' + str(organization_id) +
                ',"name":"PlayBox BR","logoPath":null,"description":"PlayBox BR","sortOrder":1}'
            )
            selected_org = (
                '{"id":' + str(organization_id) +
                ',"name":"PlayBox BR","logoPath":null,"description":"PlayBox BR","active":true}'
            )
            cookie_parts.append(f"selectedContext={selected_context}")
            cookie_parts.append(f"selectOrgnaization={selected_org}")
        if token:
            cookie_parts.append(f"accessToken={token}")
        if refresh_token:
            cookie_parts.append(f"refreshToken={refresh_token}")
        headers["cookie"] = "; ".join(cookie_parts)

        if organization_id is not None:
            headers["organizationid"] = str(organization_id)
            headers["x-context-id"] = str(organization_id)
            headers["x-context-type"] = context_type
        return headers

    def login(self, username_or_email: str, password: str, refresh_token: Optional[str] = None) -> Optional[Dict[str, Any]]:
        payload = {"usernameOrEmail": username_or_email, "password": password}
        response = self.post(
            "access/auth/login",
            headers=self.default_headers(refresh_token=refresh_token),
            payload=payload,
            service_name="Zapizi",
        )
        if isinstance(response, dict):
            self.last_token = response.get("token")
            self.last_refresh_token = response.get("refreshToken")
            return response
        return None

    def get_machines(
        self,
        token: str,
        organization_id: Optional[int] = ZAPIZI_ORGANIZATION_ID,
        page: int = 0,
        limit: int = 10,
        search: str = "",
        unique_code: str = "",
        tag: str = "",
        location_name: str = "",
        status: str = "ALL",
        machine_types: Optional[List[str]] = None,
        vend_statuses: Optional[List[str]] = None,
    ) -> List[Dict[str, Any]]:
        if not token:
            return []

        if machine_types is None:
            machine_types = ["POS"]
        if vend_statuses is None:
            vend_statuses = []

        advanced_search = {
            "search": search,
            "uniqueCode": unique_code,
            "tag": tag,
            "locationName": location_name,
            "status": status,
            "machineTypes": machine_types,
            "vendStatuses": vend_statuses,
            "orderBy": {"orderBy": "string", "order": "desc"},
        }
        params = {
            "page": page,
            "limit": limit,
            "advancedSearch": json.dumps(advanced_search),
        }
        response = self.get(
            "core/machine/",
            headers=self.default_headers(
                token=token,
                refresh_token=self.last_refresh_token,
                organization_id=organization_id,
            ),
            params=params,
            service_name="Zapizi",
        )
        if isinstance(response, dict):
            return response.get("data", []) if isinstance(response.get("data"), list) else []
        return []

    def get_reports(
        self,
        token: Optional[str],
        start_date: str,
        end_date: str,
        organization_ids: Optional[List[int]] = None,
        location_ids: Optional[List[int]] = None,
        tags: str = "",
        search: str = "",
        page: int = 0,
        limit: int = 10,
        organization_id: Optional[int] = ZAPIZI_ORGANIZATION_ID,
        refresh_token: Optional[str] = None,
    ) -> Dict[str, Any]:
        effective_token = token or self.last_token
        effective_refresh = refresh_token or self.last_refresh_token
        if not effective_token:
            return {}

        advanced_search = {
            "organizationIds": organization_ids,
            "locationIds": location_ids,
            "tags": tags,
            "search": search,
            "startDate": start_date,
            "endDate": end_date,
        }
        params = {
            "page": page,
            "limit": limit,
            "advancedSearch": json.dumps(advanced_search),
        }
        response = self.get(
            "statement/statements/sales/reports",
            headers=self.default_headers(
                token=effective_token,
                refresh_token=effective_refresh,
                organization_id=organization_id,
            ),
            params=params,
            service_name="Zapizi",
        )
        return response if isinstance(response, dict) else {}
