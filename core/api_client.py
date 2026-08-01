import json
from typing import Any, Dict, Optional, Union

import requests

JSONType = Union[Dict[str, Any], list, str, int, float, bool, None]


class IntegrationError(Exception):
    def __init__(self, service_name: str, details: Optional[str] = None):
        self.service_name = service_name
        message = f"Erro ao obter dados integracao: {service_name}"
        if details:
            message = f"{message}\n{details}"
        super().__init__(message)
        self.details = details


class APIClient:
    def __init__(self, base_url: str, timeout: int = 30):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.timeout = timeout

    def _request(
        self,
        method: str,
        path: str,
        headers: Optional[Dict[str, str]] = None,
        json_payload: Optional[Any] = None,
        params: Optional[Dict[str, Any]] = None,
        service_name: Optional[str] = None,
    ) -> Optional[JSONType]:
        url = f"{self.base_url}/{path.lstrip('/')}"
        try:
            response = self.session.request(
                method=method,
                url=url,
                headers=headers,
                json=json_payload,
                params=params,
                timeout=self.timeout,
            )
            response.raise_for_status()
        except requests.RequestException as error:
            details = None
            if hasattr(error, "response") and error.response is not None:
                try:
                    details = error.response.text
                except Exception:
                    pass
            if service_name:
                print(f"Erro ao obter dados integracao: {service_name}")
            print(f"Erro na requisição {method.upper()} {url}: {error}")
            if details:
                print(details)
            raise IntegrationError(service_name or "desconhecido", details)

        try:
            response_data = response.json()
        except json.JSONDecodeError:
            if service_name:
                print(f"Erro ao obter dados integracao: {service_name}")
            print(f"Resposta JSON inválida em {url}")
            raise IntegrationError(service_name or "desconhecido", "Resposta JSON inválida")

        if isinstance(response_data, dict) and "message" in response_data and not response_data.get("data"):
            details = response_data.get("message")
            if service_name:
                print(f"Erro ao obter dados integracao: {service_name}")
            print(details)
            raise IntegrationError(service_name or "desconhecido", details)

        return response_data

    def get(
        self,
        path: str,
        headers: Optional[Dict[str, str]] = None,
        params: Optional[Dict[str, Any]] = None,
        service_name: Optional[str] = None,
    ) -> Optional[JSONType]:
        return self._request("GET", path, headers=headers, params=params, service_name=service_name)

    def post(
        self,
        path: str,
        headers: Optional[Dict[str, str]] = None,
        payload: Optional[Any] = None,
        service_name: Optional[str] = None,
    ) -> Optional[JSONType]:
        return self._request("POST", path, headers=headers, json_payload=payload, service_name=service_name)
