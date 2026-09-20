#!/usr/bin/env python3
"""
Binary Beast — Linux Runner
سكريبت جسر الهاردوير — بيتحط تلقائيًا جوه كل توزيعة (rootfs) أثناء التثبيت.

الاستخدام من أي سكريبت شل داخل اللينكس:
    binarybeast-bridge wifi_status
    binarybeast-bridge network_status
    binarybeast-bridge bt_status
    binarybeast-bridge bt_paired_devices
    binarybeast-bridge screen_get_brightness
    binarybeast-bridge screen_set_brightness --value 180

بيرجع دايمًا JSON على stdout، وexit code = 0 لو ok=true، أو 1 لو فشل — عشان
يسهل استخدامه في سكريبتات bash عادية زي:
    if binarybeast-bridge wifi_status | grep -q '"enabled": true'; then ...

ملاحظة تقنية: الأداة دي بتتواصل مع HardwareBridgeServer.kt (شغال جوه تطبيق
أندرويد نفسه) عن طريق TCP socket محلي على 127.0.0.1 — مفيش أي اتصال
بالإنترنت أو أي جهة خارجية.
"""
import argparse
import json
import socket
import sys

BRIDGE_HOST = "127.0.0.1"
BRIDGE_PORT = 7912


def send_request(action: str, extra: dict) -> dict:
    payload = {"action": action, **extra}
    try:
        with socket.create_connection((BRIDGE_HOST, BRIDGE_PORT), timeout=5) as sock:
            sock.sendall((json.dumps(payload) + "\n").encode("utf-8"))
            response_line = sock.makefile().readline()
            return json.loads(response_line)
    except ConnectionRefusedError:
        return {"ok": False, "error": "تطبيق Binary Beast مش شغال أو الجسر متوقف"}
    except socket.timeout:
        return {"ok": False, "error": "انتهت مهلة الاتصال بالجسر"}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def main():
    parser = argparse.ArgumentParser(
        prog="binarybeast-bridge",
        description="جسر التحكم بهاردوير الموبايل من جوه اللينكس (Binary Beast)"
    )
    parser.add_argument("action", help="الأمر المطلوب، مثل wifi_status أو bt_status")
    parser.add_argument("--value", type=int, help="قيمة رقمية (تستخدم مع screen_set_brightness)")
    args = parser.parse_args()

    extra = {}
    if args.value is not None:
        extra["value"] = args.value

    result = send_request(args.action, extra)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    sys.exit(0 if result.get("ok") else 1)


if __name__ == "__main__":
    main()
