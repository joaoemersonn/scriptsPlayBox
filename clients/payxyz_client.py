from typing import Any, Dict, List, Optional

from core.api_client import APIClient


class PayXYZClient(APIClient):
    def __init__(self, timeout: int = 30):
        super().__init__("https://api2.payxyz.app.br/v2", timeout=timeout)

    @staticmethod
    def default_headers(token: Optional[str] = None) -> Dict[str, str]:
        headers = {
            "Accept": "*/*",
            "Accept-Language": "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control": "max-age=0",
            "Connection": "keep-alive",
            "Origin": "https://payxyz.app.br",
            "Referer": "https://payxyz.app.br/",
            "Sec-Fetch-Dest": "empty",
            "Sec-Fetch-Mode": "cors",
            "Sec-Fetch-Site": "same-site",
            "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
            "content-type": "application/json",
            "sec-ch-ua": '"Google Chrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"',
            "sec-ch-ua-mobile": "?0",
            "sec-ch-ua-platform": '"macOS"',
        }
        if token:
            headers["x-access-token"] = token
        return headers

    def login(self, email: str, senha: str, keep_connected: bool = True) -> Optional[str]:
        payload = {"email": email, "senha": senha, "keepConnected": keep_connected}
        response = self.post("login/login-cliente", headers=self.default_headers(), payload=payload, service_name="PayXYZ")
        return response.get("token") if isinstance(response, dict) else None

    def get_machines(self, token: Optional[str]) -> List[Dict[str, Any]]:
        if not token:
            return []

        response = self.get("maquinas", headers=self.default_headers(token), service_name="PayXYZ")
        if isinstance(response, dict):
            return response.get("maquinas", []) if isinstance(response.get("maquinas"), list) else []
        return []

    def get_report(self, token: str, start_date: str, end_date: str, maquina_ids: List[Any]) -> List[Dict[str, Any]]:
        if not token or not maquina_ids:
            return []

        payload = {"maquinaIds": maquina_ids, "dataInicio": start_date, "dataFim": end_date}
        response = self.post("maquinas/relatorios", headers=self.default_headers(token), payload=payload, service_name="PayXYZ")
        return response if isinstance(response, list) else []
