#!/bin/sh

echo 'Calling GET /admin/stop on local Qortal node'
if curl --url http://localhost:12391/admin/stop 1>/dev/null 2>&1; then
	echo "Qortal node responded and should be shutting down"
	exit 0
else
	echo "No response from Qortal node - not running?"
	exit 1
fi
