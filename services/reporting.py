from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Dict, Iterable, List, Optional, Tuple

from core.config import (
    GREENAPI_REPORT_CHAT_ID,
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


@dataclass
class MachineReport:
    id_maquina: Any
    nome_maquina: str
    valor_estornos: float = 0.0
    valor_especie: float = 0.0
    valor_pix: float = 0.0
    valor_credito: float = 0.0
    valor_debito: float = 0.0
    quantidade_premios: float = 0.0
    valor_total: float = 0.0
    receita_por_premio_liberado: float = 0.0

    def update_totals(self) -> None:
        self.valor_total = (
            self.valor_especie + self.valor_pix + self.valor_credito + self.valor_debito
        )
        self.receita_por_premio_liberado = (
            self.valor_total / self.quantidade_premios if self.quantidade_premios else 0.0
        )

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "MachineReport":
        return cls(
            id_maquina=data.get("id_maquina"),
            nome_maquina=data.get("nome_maquina", "N/A").strip(),
            valor_estornos=float(data.get("valor_estornos", 0) or 0),
            valor_especie=float(data.get("valor_especie", 0) or 0),
            valor_pix=float(data.get("valor_pix", 0) or 0),
            valor_credito=float(data.get("valor_credito", 0) or 0),
            valor_debito=float(data.get("valor_debito", 0) or 0),
            quantidade_premios=float(data.get("quantidade_premios", 0) or 0),
        )


class ReportGenerator:
    def __init__(self, timeout: int = 30):
        self.vend_client = VendProMaxClient(timeout=timeout)
        self.payxyz_client = PayXYZClient(timeout=timeout)
        self.zapizi_client = ZapiziClient(timeout=timeout)
        self.sender = WhatsAppSender(chat_id=GREENAPI_REPORT_CHAT_ID, timeout=timeout)

    def build_report_dates(self, periodo: str = "24h-anterior") -> Tuple[str, str, str]:
        hoje = datetime.now()
        ontem = hoje - timedelta(days=1)

        if periodo == "24h-anterior":
            inicio = ontem.replace(hour=0, minute=0, second=0, microsecond=0)
            fim = ontem.replace(hour=23, minute=59, second=59, microsecond=999999)
            titulo = f"Relatório de {ontem.strftime('%d/%m/%Y')}"
        elif periodo == "12h-atual":
            inicio = hoje - timedelta(hours=12)
            fim = hoje
            titulo = f"Relatório das Últimas 12h ({hoje.strftime('%d/%m/%Y')})"
        else:
            inicio = ontem.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
            fim = ontem.replace(hour=23, minute=59, second=59, microsecond=999999)
            mes_nome = self._month_name_pt(ontem.month)
            titulo = f"Relatório do Mês de {mes_nome} (até {ontem.strftime('%d/%m')})"

        inicio_str = inicio.isoformat(timespec="milliseconds") + "Z"
        fim_str = fim.isoformat(timespec="milliseconds") + "Z"
        return titulo, inicio_str, fim_str

    @staticmethod
    def _month_name_pt(month_number: int) -> str:
        names = {
            1: "Janeiro",
            2: "Fevereiro",
            3: "Março",
            4: "Abril",
            5: "Maio",
            6: "Junho",
            7: "Julho",
            8: "Agosto",
            9: "Setembro",
            10: "Outubro",
            11: "Novembro",
            12: "Dezembro",
        }
        return names.get(month_number, "")

    def build_vendpromax_report(
        self,
        inicio: str,
        fim: str,
    ) -> List[MachineReport]:
        token = self.vend_client.login(VENDPROMAX_EMAIL, VENDPROMAX_PASSWORD)
        machines = self.vend_client.get_machines(token)
        report_list: List[MachineReport] = []

        for machine in machines:
            maquina_id = machine.get("id")
            nome = machine.get("nome", "N/A").strip()
            if not maquina_id:
                print(f"Pulando máquina sem ID: {nome}")
                continue

            cash = self.vend_client.get_report(token, "relatorio-01-cash", maquina_id, inicio, fim)
            estornos = self.vend_client.get_report(token, "relatorio-04-estornos", maquina_id, inicio, fim)
            pagamentos = self.vend_client.get_report(token, "relatorio-03-pagamentos", maquina_id, inicio, fim)
            premios = self.vend_client.get_report(token, "relatorio-premios", maquina_id, inicio, fim)

            report = MachineReport(
                id_maquina=maquina_id,
                nome_maquina=nome,
                valor_estornos=float(estornos.get("valor", 0) if isinstance(estornos, dict) else 0),
                valor_especie=float(cash.get("valor", 0) if isinstance(cash, dict) else 0),
                valor_pix=float(pagamentos.get("pix", 0) if isinstance(pagamentos, dict) else 0),
                valor_credito=float(pagamentos.get("credito", 0) if isinstance(pagamentos, dict) else 0),
                valor_debito=float(pagamentos.get("debito", 0) if isinstance(pagamentos, dict) else 0),
                quantidade_premios=float(premios.get("quantidade", 0) if isinstance(premios, dict) else 0),
            )
            report.update_totals()
            report_list.append(report)

        return report_list

    def build_payxyz_report(self, inicio: str, fim: str) -> List[MachineReport]:
        token = self.payxyz_client.login(PAYXYZ_EMAIL, PAYXYZ_PASSWORD)
        machines = self.payxyz_client.get_machines(token)
        maquina_ids = [machine.get("id") for machine in machines if machine.get("id")]
        if not maquina_ids:
            return []

        raw_reports = self.payxyz_client.get_report(token, inicio, fim, maquina_ids)
        report_list: List[MachineReport] = []

        for item in raw_reports:
            maquina = item.get("maquina", {})
            maquina_id = maquina.get("id")
            nome = maquina.get("nome", "N/A").strip()
            if not maquina_id:
                continue

            valor_estornos = 0.0
            valor_especie = 0.0
            valor_pix = 0.0
            valor_credito = 0.0
            valor_debito = 0.0
            quantidade_premios = 0.0

            for payment_data in item.get("vendasByPaymentType", []):
                forma = payment_data.get("formaDePagamento")
                dados = payment_data.get("dados", {})
                valor_total = float(dados.get("valorTotal", 0) or 0)
                valor_estornos += float(dados.get("valorTotalEstorno", 0) or 0)

                if forma == "especie":
                    valor_especie += valor_total
                elif forma == "pix":
                    valor_pix += valor_total
                elif forma == "credito":
                    valor_credito += valor_total
                elif forma == "debito":
                    valor_debito += valor_total

            for day_data in item.get("vendasByDay", []):
                valor_estornos += float(day_data.get("dados", {}).get("valorTotalEstorno", 0) or 0)

            for estoque_data in item.get("faturamentoPorEstoque", []):
                quantidade_premios += float(estoque_data.get("totalEstoque", 0) or 0)

            report = MachineReport(
                id_maquina=maquina_id,
                nome_maquina=nome,
                valor_estornos=valor_estornos,
                valor_especie=valor_especie,
                valor_pix=valor_pix,
                valor_credito=valor_credito,
                valor_debito=valor_debito,
                quantidade_premios=quantidade_premios,
            )
            report.update_totals()
            report_list.append(report)

        return report_list

    def build_zapizi_report(self, inicio: str, fim: str) -> List[MachineReport]:
        login_data = self.zapizi_client.login(ZAPIZI_EMAIL, ZAPIZI_PASSWORD)
        token = login_data.get("token") if isinstance(login_data, dict) else None
        refresh_token = login_data.get("refreshToken") if isinstance(login_data, dict) else None
        organization_id = int(ZAPIZI_ORGANIZATION_ID) if ZAPIZI_ORGANIZATION_ID else None

        report_data = self.zapizi_client.get_reports(
            token=token,
            start_date=inicio,
            end_date=fim,
            organization_ids=None,
            location_ids=None,
            tags="",
            search="",
            page=0,
            limit=1000,
            organization_id=organization_id,
            refresh_token=refresh_token,
        )

        rows = report_data.get("data", []) if isinstance(report_data, dict) else []
        report_list: List[MachineReport] = []

        for item in rows:
            nome = item.get("location") or item.get("machineUniqueCode") or "N/A"
            report = MachineReport(
                id_maquina=item.get("machineUniqueCode") or item.get("location"),
                nome_maquina=str(nome).strip(),
                valor_estornos=float(item.get("refund", 0) or 0),
                valor_especie=float(item.get("cash", 0) or 0),
                valor_pix=float(item.get("pix", 0) or 0),
                valor_credito=float(item.get("credit", 0) or 0),
                valor_debito=float(item.get("debit", 0) or 0),
                quantidade_premios=float(item.get("productsDelivered", 0) or 0),
            )
            report.update_totals()
            report_list.append(report)

        return report_list

    def unify_reports(self, *reports: Iterable[MachineReport]) -> List[MachineReport]:
        unified: Dict[Tuple[Any, str], MachineReport] = {}
        for report_list in reports:
            for report in report_list:
                key = (report.id_maquina, report.nome_maquina)
                if key not in unified:
                    unified[key] = MachineReport(
                        id_maquina=report.id_maquina,
                        nome_maquina=report.nome_maquina,
                    )

                target = unified[key]
                target.valor_estornos += report.valor_estornos
                target.valor_especie += report.valor_especie
                target.valor_pix += report.valor_pix
                target.valor_credito += report.valor_credito
                target.valor_debito += report.valor_debito
                target.quantidade_premios += report.quantidade_premios
                target.update_totals()

        return sorted(
            unified.values(),
            key=lambda item: (item.nome_maquina or "", str(item.id_maquina) if item.id_maquina is not None else ""),
        )

    @staticmethod
    def format_currency(value: float) -> str:
        formatted = f"R$ {value:,.2f}"
        return formatted.replace(
            ",", "X"
        ).replace(".", ",").replace("X", ".")

    def format_whatsapp_message(self, report_data: List[MachineReport], title: str) -> str:
        lines = [f"*📊 {title} 📊*"]
        if not report_data:
            lines.append("Nenhum dado disponível para este período.")
            return "\n".join(lines)

        total_valor = sum(row.valor_total for row in report_data)
        total_premios = sum(row.quantidade_premios for row in report_data)
        receita_geral = total_valor / total_premios if total_premios else 0.0

        lines.append("*Resumo Geral:*")
        lines.append(f"💰 Valor Total: {self.format_currency(total_valor)}")
        lines.append(f"🎁 Prêmios Liberados: {int(total_premios)} unidades")
        lines.append(f"💸 Receita/Prêmio: {self.format_currency(receita_geral)}")
        lines.append("")
        lines.append("*Detalhes por Máquina:*")

        for row in report_data:
            lines.append(f"🤖 *{row.nome_maquina}*")
            lines.append(f"  💰 Total: {self.format_currency(row.valor_total)}")
            lines.append(f"  🎁 Prêmios: {int(row.quantidade_premios)}")
            lines.append(f"  💸 Receita/Prêmio: {self.format_currency(row.receita_por_premio_liberado)}")
            lines.append("")

        return "\n".join(lines).strip()

    def send_report(self, report_data: List[MachineReport], title: str) -> None:
        message = self.format_whatsapp_message(report_data, title)
        print(f"--- Mensagem WhatsApp - {title} ---")
        print(message)
        print()
        self.sender.send_message(message)
