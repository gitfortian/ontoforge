#!/usr/bin/env python3
"""M8.2 real two-JVM Bridge CAS and durable receipt acceptance.

Needs two separately launched Spring Boot processes on ports 8080/8081,
the same real PostgreSQL 17 MetaDB/source and JWT fixture, plus the Bridge
CREATE request captured from the production React browser test.
No HTTP endpoints, database facts, authorization or responses are mocked.
"""
import argparse
import json
import os
import subprocess
import uuid
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from pathlib import Path
from threading import Barrier
from urllib.error import HTTPError
from urllib.request import Request, urlopen

PATH = Path(os.environ["E2E_M8_BRIDGE_RECEIPT_EVIDENCE"])
OUTPUT = Path(os.environ["E2E_M8_BRIDGE_WORKER_EVIDENCE"])
AUTH = Path(os.environ["E2E_AUTH_FILE"])


def request(port, binding_id, payload, token):
    url = f"http://127.0.0.1:{port}/api/runtime/link-bindings/{binding_id}"
    req = Request(url, data=json.dumps(payload).encode("utf-8"), method="PUT",
                  headers={"Authorization": "Bearer " + token,
                           "Content-Type": "application/json"})
    try:
        with urlopen(req, timeout=30) as response:
            return response.status, json.loads(response.read())
    except HTTPError as error:
        return error.code, json.loads(error.read())


def pg_count(binding_id, where):
    # The binding id is parsed as UUID before interpolation, never arbitrary SQL.
    valid = str(uuid.UUID(binding_id))
    result = subprocess.check_output(["docker", "ps", "--format", "{{.ID}} {{.Image}}"],
                                     text=True)
    container = next((line.split()[0] for line in result.splitlines()
                      if len(line.split()) >= 2
                      and line.split()[1].startswith("postgres:17")), None)
    assert container, "Real PostgreSQL 17 service container was not found"
    sql = (f"SELECT {where} FROM runtime_link_binding"
           + ("_revision" if "count(" in where else "")
           + f" WHERE link_binding_id='{valid}'::uuid")
    output = subprocess.check_output(
        ["docker", "exec", container, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
         "-U", "ontology", "-d", "ontoforge_test_e2e", "-c", sql], text=True)
    return int(output.strip())


def test_two_workers(token):
    fixture = json.loads(PATH.read_text(encoding="utf-8"))
    binding_id = str(uuid.UUID(fixture["id"]))
    body = fixture["request"]
    assert body["mode"] == "BRIDGE_TABLE" and body["expectedRevision"] == 0
    assert body["packageId"] == fixture["packageId"]
    a = deepcopy(body)
    b = deepcopy(body)
    for value in (a, b):
        value["expectedRevision"] = 1
        value["idempotencyKey"] = str(uuid.uuid4())
    barrier = Barrier(3)

    def send(port, payload):
        barrier.wait(timeout=20)
        return port, payload, request(port, binding_id, payload, token)

    with ThreadPoolExecutor(max_workers=2) as executor:
        fa = executor.submit(send, 8080, a)
        fb = executor.submit(send, 8081, b)
        barrier.wait(timeout=20)
        rows = [fa.result(timeout=45), fb.result(timeout=45)]
    statuses = sorted(entry[2][0] for entry in rows)
    assert statuses == [200, 409], (
        "Two JVMs must yield exactly one winner and one stale CAS", statuses)
    winner = next(entry for entry in rows if entry[2][0] == 200)
    loser = next(entry for entry in rows if entry[2][0] == 409)
    assert loser[2][1].get("code") == "BINDING_REVISION_CONFLICT", loser[2][1]
    for forbidden in ("sourceReadRefs", "objects", "links", "validationQuality"):
        assert forbidden not in loser[2][1], "A failed CAS exposed business evidence"
    detail = winner[2][1]
    assert detail["revision"] == 2 and detail["linkBindingId"] == binding_id, detail
    assert pg_count(binding_id, "count(*) FILTER (WHERE revision=2)") == 1
    assert pg_count(binding_id, "current_revision") == 2

    # A client that lost the response may replay the winner operation on the
    # OTHER JVM. The same durable receipt must come back, no revision 3.
    second_port = loser[0]
    status, replay = request(second_port, binding_id, winner[1], token)
    assert status == 200 and replay["revision"] == 2, (status, replay)
    assert replay["digest"] == detail["digest"], (replay, detail)
    changed = deepcopy(winner[1])
    changed["expectedRevision"] = 2  # changed body, SAME operation UUID
    conflict, conflict_body = request(second_port, binding_id, changed, token)
    assert conflict == 409 and conflict_body.get("code") == "IDEMPOTENCY_KEY_CONFLICT", (
        conflict, conflict_body)
    assert pg_count(binding_id, "count(*) FILTER (WHERE revision=2)") == 1
    assert pg_count(binding_id, "current_revision") == 2

    OUTPUT.write_text(json.dumps({
        "id": binding_id, "packageId": fixture["packageId"],
        "request": winner[1], "revision": 2, "digest": detail["digest"],
        "winnerPort": winner[0], "replayPort": second_port
    }, indent=2), encoding="utf-8")
    print("Bridge two independent JVMs: 200/409 single CAS winner, cross-JVM "
          "same-UUID replay, changed body refused, one immutable revision")


def test_restart_replay(token):
    fixture = json.loads(OUTPUT.read_text(encoding="utf-8"))
    binding_id = str(uuid.UUID(fixture["id"]))
    status, result = request(8080, binding_id, fixture["request"], token)
    assert status == 200 and result["revision"] == 2, (status, result)
    assert result["digest"] == fixture["digest"], result
    assert pg_count(binding_id, "count(*) FILTER (WHERE revision=2)") == 1
    assert pg_count(binding_id, "current_revision") == 2
    changed = deepcopy(fixture["request"])
    changed["expectedRevision"] = 2
    status, result = request(8080, binding_id, changed, token)
    assert status == 409 and result.get("code") == "IDEMPOTENCY_KEY_CONFLICT", (
        status, result)
    print("Bridge real JVM restart: durable same-UUID receipt survived; "
          "no new revision; changed body refused")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--restart-replay", action="store_true")
    args = parser.parse_args()
    token = json.loads(AUTH.read_text(encoding="utf-8"))["editorToken"]
    if args.restart_replay:
        test_restart_replay(token)
    else:
        test_two_workers(token)
