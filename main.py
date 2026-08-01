import argparse

from core.api_client import IntegrationError
from services.monitoring import OfflineMonitor
from services.reporting import ReportGenerator


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Ferramenta unificada de relatórios e monitoramento de máquinas",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""Subcomandos:
  report --periodo {24h-anterior,12h-atual,mes}
  monitor
""",
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    report_parser = subparsers.add_parser("report", help="Gerar relatório consolidado de máquinas")
    report_parser.add_argument(
        "--periodo",
        choices=["24h-anterior", "12h-atual", "mes"],
        default="24h-anterior",
        help="Período do relatório (padrão: 24h-anterior)",
    )

    subparsers.add_parser("monitor", help="Verificar máquinas offline e enviar alertas")

    args = parser.parse_args()

    if args.command == "report":
        generator = ReportGenerator()
        try:
            title, start_date, end_date = generator.build_report_dates(args.periodo)
            vend_report = generator.build_vendpromax_report(start_date, end_date)
            pay_report = generator.build_payxyz_report(start_date, end_date)
            zapizi_report = generator.build_zapizi_report(start_date, end_date)
            unified_report = generator.unify_reports(vend_report, pay_report, zapizi_report)
            generator.send_report(unified_report, title)
        except IntegrationError as error:
            generator.sender.send_message(str(error))
            return

    elif args.command == "monitor":
        monitor = OfflineMonitor()
        monitor.run()


if __name__ == "__main__":
    main()
