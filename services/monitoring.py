from pathlib import Path
from typing import Dict, Iterable, List, Optional, Set

from core.api_client import IntegrationError
from core.config import (
    PAYXYZ_EMAIL,
    PAYXYZ_PASSWORD,
    VENDPROMAX_EMAIL,
    VENDPROMAX_PASSWORD,
    ZAPIZI_EMAIL,
    ZAPIZI_PASSWORD,
    ZAPIZI_ORGANIZATION_ID,
)
from clients.payxyz_client import PayXYZClient
from clients.vendpromax_client import VendProMaxClient
from clients.zapizi_client import ZapiziClient
from core.whatsapp import WhatsAppSender


class OfflineMonitor:
    def __init__(self, timeout: int = 30):
        self.vend_client = VendProMaxClient(timeout=timeout)
        self.payxyz_client = PayXYZClient(timeout=timeout)
        self.zapizi_client = ZapiziClient(timeout=timeout)
        self.sender = WhatsAppSender(timeout=timeout)

    @staticmethod
    def _line_set(lines: Iterable[str]) -> Set[str]:
        return {line.strip() for line in lines if line.strip()}

    def load_file_set(self, filepath: Path) -> Set[str]:
        try:
            with filepath.open("r", encoding="utf-8") as file:
                return self._line_set(file)
        except FileNotFoundError:
            return set()
        except OSError as error:
            print(f"Erro ao ler arquivo {filepath}: {error}")
            return set()

    def save_file_set(self, filepath: Path, values: Iterable[str]) -> None:
        try:
            with filepath.open("w", encoding="utf-8") as file:
                for value in sorted(set(values)):
                    file.write(f"{value}\n")
        except OSError as error:
            print(f"Erro ao salvar arquivo {filepath}: {error}")

    def load_file_text(self, filepath: Path) -> str:
        try:
            return filepath.read_text(encoding="utf-8")
        except FileNotFoundError:
            return ""
        except OSError as error:
            print(f"Erro ao ler arquivo {filepath}: {error}")
            return ""

    def save_file_text(self, filepath: Path, text: str) -> None:
        try:
            filepath.write_text(text, encoding="utf-8")
        except OSError as error:
            print(f"Erro ao salvar arquivo {filepath}: {error}")

    def get_offline_machine_names(self, machines: List[dict]) -> List[str]:
        offline = []
        for machine in machines:
            status = machine.get("status") or machine.get("statusMaquina")
            if status and str(status).upper() == "OFFLINE":
                name = machine.get("nome")
                if name:
                    normalized = str(name).strip()
                    if normalized:
                        offline.append(normalized)
        return offline

    @staticmethod
    def build_message(title: str, machine_names: Iterable[str]) -> str:
        names = sorted({name for name in machine_names if name})
        if not names:
            return ""
        lines = [title]
        lines.extend(f"• {name}" for name in names)
        return "\n".join(lines)

    def send_status_alert(self, message: str) -> None:
        if not message:
            print("Nenhuma mudança de status para notificar.")
            return
        print(message)
        self.sender.send_message(message)

    def send_integration_error(self, service_name: str, details: Optional[str] = None) -> None:
        message = f"Erro ao obter dados integracao: {service_name}"
        if details:
            message = f"{message}\n{details}"
        print(message)
        self.sender.send_message(message)

    def run(self) -> None:
        base_path = Path(__file__).resolve().parent
        previous_offline_file = base_path / "offline_status.txt"
        previous_message_file = base_path / "last_offline_notification.txt"
        notified_back_online_file = base_path / "notified_back_online.txt"

        previous_offline = self.load_file_set(previous_offline_file)
        previous_message = self.load_file_text(previous_message_file)
        notified_back_online = self.load_file_set(notified_back_online_file)

        try:
            vend_token = self.vend_client.login(VENDPROMAX_EMAIL, VENDPROMAX_PASSWORD)
            vend_machines = self.vend_client.get_machines(vend_token)
            vend_offline = self.get_offline_machine_names(vend_machines)

            pay_token = self.payxyz_client.login(PAYXYZ_EMAIL, PAYXYZ_PASSWORD)
            pay_machines = self.payxyz_client.get_machines(pay_token)
            pay_offline = self.get_offline_machine_names(pay_machines)

            zap_token_data = self.zapizi_client.login(ZAPIZI_EMAIL, ZAPIZI_PASSWORD)
            zap_token = zap_token_data.get("token") if isinstance(zap_token_data, dict) else None
            zap_org_id = int(ZAPIZI_ORGANIZATION_ID) if ZAPIZI_ORGANIZATION_ID else None
            zap_machines = self.zapizi_client.get_machines(
                token=zap_token,
                organization_id=zap_org_id,
                status="OFFLINE",
                page=0,
                limit=1000,
                machine_types=["POS"],
            )
            zap_offline = []
            for machine in zap_machines:
                if str(machine.get("status", "")).upper() != "OFFLINE":
                    continue
                name = machine.get("locationName") or machine.get("uniqueCode") or machine.get("name")
                if name:
                    normalized = str(name).strip()
                    if normalized:
                        zap_offline.append(normalized)
        except IntegrationError as error:
            self.send_integration_error(error.service_name, error.details)
            return

        current_offline = sorted(set(vend_offline + pay_offline + zap_offline))
        current_offline_set = set(current_offline)

        print("Máquinas VendProMax offline:", vend_offline)
        print("Máquinas PayXYZ offline:", pay_offline)
        print("Máquinas Zapizi offline:", zap_offline)
        print("Status anterior offline:", sorted(previous_offline))
        print("Status atual offline:", current_offline)

        notified_back_online -= current_offline_set

        became_online = sorted(previous_offline - current_offline_set)
        new_back_online = [name for name in became_online if name not in notified_back_online]

        back_online_message = self.build_message("*🟢DE VOLTA ONLINE:*", new_back_online)
        if back_online_message:
            self.send_status_alert(back_online_message)
            notified_back_online.update(new_back_online)

        previous_offline_set = set(previous_offline)
        old_offline = sorted(current_offline_set & previous_offline_set)
        new_offline = sorted(current_offline_set - previous_offline_set)

        sections = []
        if new_offline:
            sections.append(self.build_message("*🔴NOVA MAQUINA OFFLINE:*", new_offline))
        if old_offline:
            sections.append(self.build_message("*🔴OFFLINE:*", old_offline))

        offline_message = "\n\n".join([section for section in sections if section])
        self.save_file_set(previous_offline_file, current_offline)
        self.save_file_set(notified_back_online_file, notified_back_online)

        if not offline_message:
            print("Nenhuma máquina offline.")
            return

        message_text = offline_message.strip()
        if message_text == previous_message.strip():
            print("Mensagem OFFLINE igual à anterior. Nenhuma notificação enviada.")
            return

        print("\nMensagem OFFLINE gerada:")
        self.send_status_alert(message_text)
        self.save_file_text(previous_message_file, message_text)
