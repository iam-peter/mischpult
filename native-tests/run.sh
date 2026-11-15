#!/bin/sh
# Builds and runs the host-side deck tests with the system compiler.
set -e
cd "$(dirname "$0")"
CPP=../app/src/main/cpp
OUT=${TMPDIR:-/tmp}/mischpult_deck_test
c++ -std=c++17 -O2 -I "$CPP" -I "$CPP/third_party/soundtouch/include" \
    deck_test.cpp "$CPP/Deck.cpp" "$CPP/Track.cpp" "$CPP"/third_party/soundtouch/source/*.cpp \
    -w -o "$OUT"
"$OUT"
