import requests
import uuid
import os

# Şifreyi kodun içine yazmak yerine işletim sisteminden çekiyoruz.
# Eğer sistemde bulamazsa varsayılan olarak yer tutucu (hata verecek) bir metin kullanır.
GIZLI_SIFRE = os.environ.get("WEBHOOK_SECRET", "GUVENLIK_ICIN_SIFREYI_ORTAM_DEGISKENINDEN_VERIN")

veri = {
    "signalId": str(uuid.uuid4()),
    "symbol": "BTCUSDT",
    "action": "BUY",
    "price": 65000.0,
    "quantity": 0.001  # Sözdizimi hatası düzeltildi
}

ozel_basliklar = {
    "X-Webhook-Secret": GIZLI_SIFRE
}

# Testi yerel sunucuya gönderiyoruz
cevap = requests.post(
    "http://localhost:8080/api/webhook",
    json=veri,
    headers=ozel_basliklar
)

print("Cevap Kodu:", cevap.status_code)
print("Mesaj:", cevap.text)