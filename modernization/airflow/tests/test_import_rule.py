"""The import rule: DAG files import only Airflow and carddemo_airflow, and
carddemo_airflow reaches the data only through batch-service."""

from __future__ import annotations

import ast
import glob
import os

import pytest

from conftest import AIRFLOW_DIR, DAGS_DIR

DAG_FILES = sorted(glob.glob(os.path.join(DAGS_DIR, "*.py")))
PACKAGE_FILES = sorted(glob.glob(os.path.join(AIRFLOW_DIR, "carddemo_airflow", "*.py")))
# Database drivers and ORMs: the shared package must never touch the data.
DATA_ACCESS = {"sqlalchemy", "psycopg", "psycopg2", "pyodbc", "pymssql", "airflow.providers.postgres",
               "airflow.providers.microsoft", "airflow.providers.common.sql", "airflow.hooks"}


def imported_modules(path: str) -> set:
    with open(path, encoding="utf-8") as f:
        tree = ast.parse(f.read(), path)
    found = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            found.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            if node.level:
                raise AssertionError(f"{path}: relative import")
            found.add(node.module)
    return found


def test_there_are_dag_files():
    assert len(DAG_FILES) == 7


@pytest.mark.parametrize("path", DAG_FILES, ids=os.path.basename)
def test_dag_imports_only_airflow_and_the_package(path):
    for module in imported_modules(path):
        root = module.split(".")[0]
        assert root in ("airflow", "carddemo_airflow", "__future__"), f"{os.path.basename(path)} imports {module}"


@pytest.mark.parametrize("path", PACKAGE_FILES, ids=os.path.basename)
def test_package_has_no_data_access(path):
    for module in imported_modules(path):
        assert not any(module == m or module.startswith(m + ".") for m in DATA_ACCESS), \
            f"{os.path.basename(path)} imports {module}"
