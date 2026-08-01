from typing import Any, Dict, List, Optional

from core.api_client import APIClient


class VendProMaxClient(APIClient):
    def __init__(self, timeout: int = 30):
        super().__init__("https://api.vendpromax.com.br/api", timeout=timeout)

    @staticmethod
    def default_headers(token: Optional[str] = None) -> Dict[str, str]:
        headers = {
            "sec-ch-ua-platform": '"macOS"',
            "Referer": "https://sistema.vendpromax.com.br/",
            "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
            "Accept": "application/json, text/plain, */*",
            "sec-ch-ua": '"Google Chrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"',
            "Content-Type": "application/json",
            "sec-ch-ua-mobile": "?0",
        }
        if token:
            headers["x-access-token"] = token
        return headers

    def login(self, email: str, senha: str) -> Optional[str]:
        payload = {"email": email, "senha": senha}
        response = self.post("login-cliente", headers=self.default_headers(), payload=payload, service_name="VendProMax")
        return response.get("token") if isinstance(response, dict) else None

    def get_machines(self, token: Optional[str]) -> List[Dict[str, Any]]:
        if not token:
            return []

        response = self.get("maquinas", headers=self.default_headers(token), service_name="VendProMax")
        return response if isinstance(response, list) else []

    def get_report(
        self,
        token: str,
        endpoint: str,
        maquina_id: Any,
        data_inicio: str,
        data_fim: str,
        is_adm: bool = False,
    ) -> Optional[Dict[str, Any]]:
        if not token:
            return None

        payload = {
            "dataInicio": data_inicio,
            "dataFim": data_fim,
            "maquinaId": maquina_id,
            "isAdm": is_adm,
        }
        response = self.post(endpoint, headers=self.default_headers(token), payload=payload, service_name="VendProMax")
        return response if isinstance(response, dict) else None
