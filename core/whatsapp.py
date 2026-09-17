from typing import Optional

from core.api_client import APIClient
from core.config import GREENAPI_BASE_URL, GREENAPI_SEND_PATH


class WhatsAppSender:
    def __init__(self, base_url: str = GREENAPI_BASE_URL, chat_id: str = "", send_path: str = GREENAPI_SEND_PATH, timeout: int = 30):
        self.client = APIClient(base_url, timeout=timeout)
        self.chat_id = chat_id
        self.send_path = send_path

    def send_message(self, message: str) -> bool:
        if not message:
            print("Mensagem WhatsApp vazia, nenhum envio realizado.")
            return False

        payload = {"chatId": self.chat_id, "message": message}
        response = self.client.post(self.send_path, headers={"Content-Type": "application/json"}, payload=payload)

        if response is not None:
            print("Mensagem WhatsApp enviada com sucesso.")
            return True

        return False
