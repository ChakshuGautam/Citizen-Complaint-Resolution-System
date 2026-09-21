#!/usr/bin/env bash
# seed-employees.sh - put the 2.x HRMS employees of one tenant into the DIGIT
# 3.0 employee registry (employee_v3), so the shim's employee map has something
# to point at.
#
# WHY THIS IS SQL AND NOT AN API CALL. POST /employee/v3/employees validates the
# supplied userID against Keycloak, and the deployed d3-employee binary takes
# its Keycloak base URL from KEYCLOAK_BASE_URL, which this stack's compose file
# does NOT set. The compiled-in default is https://digit-lts.digit.org/keycloak
# - the DIGIT LTS demo environment - and the realm it asks for is the tenant
# code upper-cased:
#
#   failed to validate user ID: keycloak service request failed:
#   Get "https://digit-lts.digit.org/keycloak/admin/realms/PG/users/<id>":
#   dial tcp: lookup digit-lts.digit.org: no such host
#
# So on this box - and on any self-hosted v3 stack without egress to DIGIT's
# demo environment, or without a Keycloak realm per tenant already provisioned -
# an employee cannot be created through the API at all. This script writes the
# rows that API would have written. It is the ETL, not a workaround: a real
# migration has the same two prerequisites (a reachable Keycloak, a realm per
# tenant, a user per employee) and they are costed as identity-plan tasks B2/C1.
#
# Source of truth: eg_hrms_employee + its CURRENT eg_hrms_assignment, in the 2.x
# database. Read-only there; writes only to the v3 database.
set -euo pipefail
TENANT="${TENANT:-pg}"
SRC="${SRC:-docker-postgres}"       # 2.x postgres (db egov, user egov)
DST="${DST:-d3-postgres}"           # v3 postgres (db postgres, user postgres)

# One row per HRMS employee in $TENANT, carrying the department/designation of
# the employee's CURRENT assignment. Everything else in eg_hrms_assignment -
# position, govtordernumber, ishod, fromdate/todate, and the whole assignment
# HISTORY - has no column in employee_v3 and is dropped here.
docker exec "$SRC" psql -U egov -d egov -At -F'|' -c "
  select e.uuid, e.code, coalesce(a.department,''), coalesce(a.designation,''),
         e.employeestatus, e.employeetype
  from eg_hrms_employee e
  left join eg_hrms_assignment a
    on a.employeeid = e.uuid and a.iscurrentassignment = true
  where e.tenantid = '${TENANT}'
  order by e.code;
" | while IFS='|' read -r uuid code dept desig status etype; do
  [ -n "$uuid" ] || continue
  docker exec "$DST" psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c "
    INSERT INTO employee_v3 (id, code, user_id, individual_id, status, employee_type,
                             date_of_appointment, department, designation, is_active, tenant_id,
                             \"createdBy\", \"modifiedBy\", \"createdTime\", \"modifiedTime\")
    SELECT gen_random_uuid(), '${code}', '${uuid}', NULL, '${status}', '${etype}', now(),
           '${dept}', '${desig}', true, '${TENANT}',
           'ccrs-hrms-etl-poc','ccrs-hrms-etl-poc',
           extract(epoch from now())*1000, extract(epoch from now())*1000
    WHERE NOT EXISTS (SELECT 1 FROM employee_v3 WHERE code='${code}' AND tenant_id='${TENANT}');
  " >/dev/null
  echo "employee_v3 <- ${code} (2.x ${uuid})"
done

# individual_id is left NULL on purpose. v3's employee is an EMPLOYMENT record
# that points at a person in the individual registry; creating that person is
# the citizen-side ETL (identity plan C1) and has not run for employees. The
# visible consequence is that a v3 employee has no name.
docker exec "$DST" psql -U postgres -d postgres -c \
  "select code, user_id, department, designation, tenant_id from employee_v3 where tenant_id='${TENANT}' order by code;"
