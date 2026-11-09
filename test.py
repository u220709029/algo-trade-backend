import requests
import uuid

veri = {
    "symbol": "BTCUSDT",
    "action": "BUY",
    "price": 65000.0,
    "signalId": str(uuid.uuid4()),
    "quantity": 0.05
}

ozel_basliklar = {
    "X-Webhook-Secret": "sifre5248"
}

cevap = requests.post(
    "http://localhost:8080/api/webhook",
    json=veri,
    headers=ozel_basliklar
)

print("Sunucu Cevap Kodu:", cevap.status_code)
print("Sunucu Mesajı:", cevap.text)