#!/usr/bin/env bash
# Demostracion de punta a punta contra el stack de docker compose (requiere: curl, docker).
# Uso: ./demo.sh            (con el stack levantado: docker compose up --build -d)
set -u
GW="${GW:-http://localhost:8080}"
CORE_ADMIN="${CORE_ADMIN:-http://localhost:8089/__admin}"
CLAVE="demo-$(date +%s)"
DC="docker compose"

titulo() { printf '\n=== %s ===\n' "$1"; }
post() { # $1 ruta, $2 clave, $3 cuerpo
  curl -s -o /tmp/demo_body -w '%{http_code}' -X POST "$GW$1" \
    -H 'Content-Type: application/json' -H "Idempotency-Key: $2" -H 'X-Usuario: cliente-demo' -d "$3"
}
CUERPO='{"cuentaOrigen":"1000000001","cuentaDestino":"2000000002","monto":125.50,"moneda":"USD","referencia":"Arriendo octubre"}'

titulo "1. Transferencia propia (Idempotency-Key=$CLAVE) -> 201"
echo "HTTP $(post /api/v1/transferencias/propias "$CLAVE" "$CUERPO")"; cat /tmp/demo_body; echo

titulo "2. Reintento con la MISMA clave y mismo cuerpo -> 200 + Idempotent-Replayed (misma transferencia)"
curl -s -D - -o /tmp/demo_body -X POST "$GW/api/v1/transferencias/propias" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $CLAVE" -H 'X-Usuario: cliente-demo' -d "$CUERPO" | grep -iE '^HTTP|idempotent-replayed|x-request-id'
cat /tmp/demo_body; echo

titulo "3. Misma clave, cuerpo distinto (monto 999) -> 422 (reutilizacion indebida, ADR-009)"
echo "HTTP $(post /api/v1/transferencias/propias "$CLAVE" "${CUERPO/125.50/999.00}")"; cat /tmp/demo_body; echo

titulo "4. Misma clave en otro recurso (interbancaria) -> 422 (reutilizacion indebida, ADR-009)"
# Nota: 409 se reserva para una clave con solicitud aun EN CURSO (carrera concurrente); no se ejercita aqui.
echo "HTTP $(post /api/v1/transferencias/interbancarias "$CLAVE" '{"cuentaOrigen":"1000000001","cuentaDestino":"2000000002","bancoDestino":"BANCO-X","monto":125.50}')"; cat /tmp/demo_body; echo

titulo "5. Outbox -> relay -> Valkey Stream 'transferencias.eventos'"
$DC exec -T postgres psql -U poc -d transferencias -c "SELECT tipo, agregado_id, publicado_en IS NOT NULL AS publicado FROM outbox;"
$DC exec -T valkey valkey-cli XLEN transferencias.eventos

titulo "6. Movimientos: 1a consulta (cache miss -> CORE), 2a consulta (cache hit -> CACHE)"
for i in 1 2; do
  curl -s -D - -o /dev/null "$GW/api/v1/cuentas/1000000001/movimientos" | grep -iE '^HTTP|x-origen-datos|x-respuesta-degradada'
done

titulo "7. Core caido (WireMock inyecta CONNECTION_RESET) + expira el cache vigente -> CACHE_RESPALDO degradado"
curl -s -o /dev/null -X POST "$CORE_ADMIN/mappings" -H 'Content-Type: application/json' \
  -d '{"priority":1,"request":{"method":"GET","urlPathPattern":"/core/cuentas/.*"},"response":{"fault":"CONNECTION_RESET_BY_PEER"}}'
$DC exec -T valkey sh -c "valkey-cli --scan --pattern 'movimientos:vigente:*' | xargs -r valkey-cli DEL" >/dev/null
curl -s -D - "$GW/api/v1/cuentas/1000000001/movimientos" | grep -iE '^HTTP|x-origen-datos|x-respuesta-degradada|"origen"' | cut -c1-200

titulo "8. Cuenta sin copia de respaldo -> 503; tras varias fallas el circuit breaker se ABRE"
for i in 1 2 3 4 5; do
  printf 'intento %s -> HTTP %s\n' "$i" "$(curl -s -o /dev/null -w '%{http_code}' "$GW/api/v1/cuentas/3000000003/movimientos")"
done
$DC exec -T app wget -qO- http://localhost:8080/actuator/circuitbreakers | grep -oE '"state":"[A-Z_]+"|"failureRate":"[^"]+"'
curl -s -o /dev/null -X POST "$CORE_ADMIN/mappings/reset"   # restaura los stubs del Core

titulo "9. Verificacion de la cadena de auditoria -> integra"
curl -s -w '\nHTTP %{http_code}\n' "$GW/api/v1/auditoria/verificacion"
$DC exec -T postgres psql -U poc -d transferencias -c "SELECT id, accion, left(hash_anterior,12) AS hash_anterior, left(hash,12) AS hash FROM auditoria ORDER BY id LIMIT 5;"

titulo "10. Alteracion maliciosa directa en BD (UPDATE detalle del registro 1) -> verificacion detecta (409)"
$DC exec -T postgres psql -U poc -d transferencias -c "UPDATE auditoria SET detalle = replace(detalle,'125.50','1.00') WHERE id = 1;"
curl -s -w '\nHTTP %{http_code}\n' "$GW/api/v1/auditoria/verificacion"
