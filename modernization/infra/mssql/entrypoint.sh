#!/bin/sh
# Entrypoint of the mssql service. SQL Server reads MSSQL_SA_PASSWORD only on
# the start that creates master; after that the password lives in master. So
# only that first start gets it, from the compose secret: a password in the
# service's environment would show in docker inspect and in the environment
# of every process in the container (ps e) for as long as the container lives.
set -eu

secret=/run/secrets/mssql_sa_password
unset MSSQL_SA_PASSWORD SA_PASSWORD
if [ ! -f /var/opt/mssql/data/master.mdf ]; then
	if [ ! -s "$secret" ]; then
		echo "mssql-entrypoint: $secret is missing or empty; run make setup" >&2
		exit 1
	fi
	MSSQL_SA_PASSWORD=$(cat "$secret")
	export MSSQL_SA_PASSWORD
fi
exec /opt/mssql/bin/launch_sqlservr.sh "$@"
