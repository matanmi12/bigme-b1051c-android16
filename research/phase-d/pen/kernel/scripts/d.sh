#!/bin/sh
# wrapper: d.sh NAME [--len N]
P=~/Desktop/Bigme
exec $P/tools/venv/bin/python -I $P/research/phase-d/pen/kernel/scripts/kdis.py $P/research/phase-d/pen/kernel/bin/Image $P/research/phase-d/pen/kallsyms-20261020.txt "$@"
