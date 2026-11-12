"""The stack's Airflow auth manager: Airflow's simple auth manager, plus one
service account for transaction-service that may only trigger the report.

transaction-service signs on as CARDDEMO_AIRFLOW_REPORT_USER (POST
/auth/token) and starts carddemo_transaction_report runs (POST
/api/v2/dags/carddemo_transaction_report/dagRuns). The simple auth manager
has no per-DAG permissions: its lowest role that may trigger a run, USER,
may also trigger, clear, pause or delete every DAG. So every check that
needs a permission refuses this account, reads included, whatever role the
user list gives it; the admin and any other user keep the simple auth
manager's rules. What needs no permission stays open to it as to anyone
signed on: health, version, the API docs, the login pages and its own UI
metadata (its name, an empty menu, the UI settings). A backfill of the
report DAG is a POST on its runs as well, so it passes here; Airflow refuses
it because the DAG has no schedule.

The restriction follows the configured name. Airflow accepts a token until
it expires (jwt_expiration_time) without looking the name up again, so a
token issued before CARDDEMO_AIRFLOW_REPORT_USER was renamed keeps its role,
unrestricted; stack.py warns on a rename, and rotating
AIRFLOW_API_JWT_SECRET ends those tokens.
"""

from __future__ import annotations

import os

from airflow.api_fastapi.auth.managers.models.resource_details import DagAccessEntity
from airflow.api_fastapi.auth.managers.simple.simple_auth_manager import SimpleAuthManager

REPORT_DAG_ID = "carddemo_transaction_report"
REPORT_USER_ENV = "CARDDEMO_AIRFLOW_REPORT_USER"


def report_user() -> str | None:
    return os.environ.get(REPORT_USER_ENV, "").strip() or None


class CarddemoAuthManager(SimpleAuthManager):
    @staticmethod
    def _is_report_user(user) -> bool:
        name = report_user()
        return name is not None and getattr(user, "username", None) == name

    def is_authorized_dag(self, *, method, user, access_entity=None, details=None) -> bool:
        if self._is_report_user(user):
            return (method == "POST" and access_entity == DagAccessEntity.RUN
                    and details is not None and details.id == REPORT_DAG_ID)
        return super().is_authorized_dag(method=method, user=user, access_entity=access_entity,
                                         details=details)

    # Every other resource check of the simple auth manager ends here.
    def _is_authorized(self, *, method, allow_role, user, allow_get_role=None, team_name=None) -> bool:
        if self._is_report_user(user):
            return False
        return SimpleAuthManager._is_authorized(method=method, allow_role=allow_role, user=user,
                                                allow_get_role=allow_get_role, team_name=team_name)

    def is_authorized_team(self, *, method, user, details=None) -> bool:
        if self._is_report_user(user):
            return False
        return super().is_authorized_team(method=method, user=user, details=details)

    def is_authorized_hitl_task(self, *, assigned_users, user) -> bool:
        if self._is_report_user(user):
            return False
        return super().is_authorized_hitl_task(assigned_users=assigned_users, user=user)

    def filter_authorized_menu_items(self, menu_items, *, user):
        if self._is_report_user(user):
            return []
        return super().filter_authorized_menu_items(menu_items, user=user)
