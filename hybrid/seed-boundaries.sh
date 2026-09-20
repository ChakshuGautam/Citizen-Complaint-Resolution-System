#!/usr/bin/env bash
# Seed the DIGIT 3.0 boundary store with the ADMIN hierarchy and boundaries
# that CCRS tenant `pg` already has in 2.x, so ServiceRequestValidator.validateBoundary
# can be served by v3. Idempotent-ish: re-running reports 409/duplicate, which is fine.
#
# Source of truth is the LIVE 2.x data, read straight out of the CCRS db:
#   select code, tenantid, geometry from boundary where tenantid='pg';
#   select code, boundarytype, parent, hierarchytype from boundary_relationship;
# ADMIN chain: State(PG_STATE) > City(PG_CITYA) > Zone(PG_CITYA_Z1) > Block(PG_CITYA_B1) > Locality(SUN01_LOCALITY)
set -u
V3=${V3:-http://localhost:8000}
TENANT=${TENANT:-pg}
H=(-H "X-Tenant-ID: ${TENANT}" -H "X-User-ID: hybrid-shim-task4" -H 'Content-Type: application/json')

post() { # post <path> <json>
  printf '\n--- POST %s\n' "$1"
  curl -s -m 20 -X POST "${H[@]}" -d "$2" -w '\nHTTP %{http_code}\n' "${V3}$1"
}

post /boundary/v3/hierarchy '{"hierarchy":{"hierarchyType":"ADMIN","boundaryHierarchy":[
  {"boundaryType":"State","parentBoundaryType":null,"active":true},
  {"boundaryType":"City","parentBoundaryType":"State","active":true},
  {"boundaryType":"Zone","parentBoundaryType":"City","active":true},
  {"boundaryType":"Block","parentBoundaryType":"Zone","active":true},
  {"boundaryType":"Locality","parentBoundaryType":"Block","active":true}]}}'

post /boundary/v3/boundaries '{"boundary":[
  {"code":"PG_STATE","geometry":{"type":"Polygon","coordinates":[[[74.0,30.0],[78.0,30.0],[78.0,33.0],[74.0,33.0],[74.0,30.0]]]},"additionalAttributes":{}},
  {"code":"PG_CITYA","geometry":{"type":"Polygon","coordinates":[[[74.8,31.5],[74.9,31.5],[74.9,31.7],[74.8,31.7],[74.8,31.5]]]},"additionalAttributes":{}},
  {"code":"PG_CITYA_Z1","geometry":{"type":"Polygon","coordinates":[[[74.82,31.55],[74.88,31.55],[74.88,31.65],[74.82,31.65],[74.82,31.55]]]},"additionalAttributes":{}},
  {"code":"PG_CITYA_B1","geometry":{"type":"Polygon","coordinates":[[[74.83,31.57],[74.87,31.57],[74.87,31.63],[74.83,31.63],[74.83,31.57]]]},"additionalAttributes":{}},
  {"code":"SUN01_LOCALITY","geometry":{"type":"Point","coordinates":[74.87155,31.63089]},"additionalAttributes":{}}]}'

post /boundary/v3/relationship '{"relationship":{"code":"PG_STATE","hierarchyType":"ADMIN","boundaryType":"State"}}'
post /boundary/v3/relationship '{"relationship":{"code":"PG_CITYA","hierarchyType":"ADMIN","boundaryType":"City","parent":"PG_STATE"}}'
post /boundary/v3/relationship '{"relationship":{"code":"PG_CITYA_Z1","hierarchyType":"ADMIN","boundaryType":"Zone","parent":"PG_CITYA"}}'
post /boundary/v3/relationship '{"relationship":{"code":"PG_CITYA_B1","hierarchyType":"ADMIN","boundaryType":"Block","parent":"PG_CITYA_Z1"}}'
post /boundary/v3/relationship '{"relationship":{"code":"SUN01_LOCALITY","hierarchyType":"ADMIN","boundaryType":"Locality","parent":"PG_CITYA_B1"}}'

printf '\n--- VERIFY: GET /boundary/v3/boundaries?codes=SUN01_LOCALITY\n'
curl -s -m 20 "${H[@]}" "${V3}/boundary/v3/boundaries?codes=SUN01_LOCALITY" -w '\nHTTP %{http_code}\n'
