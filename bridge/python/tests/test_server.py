"""Integration tests for the KCP MCP server."""
import json
from pathlib import Path

import pytest
from mcp.server import Server
from mcp.types import (
    ListResourcesRequest,
    ReadResourceRequest,
    ReadResourceRequestParams,
)
from pydantic import AnyUrl

from kcp_mcp.server import create_server

MINIMAL_DIR = Path(__file__).parent / "fixtures" / "minimal"
FULL_DIR = Path(__file__).parent / "fixtures" / "full"


def get_server(fixture_dir: Path, agent_only: bool = False) -> Server:
    return create_server(
        fixture_dir / "knowledge.yaml",
        agent_only=agent_only,
        warn_on_validation=False,
    )


async def call_list_resources(server: Server) -> list:
    handler = server.request_handlers[ListResourcesRequest]
    result = await handler(ListResourcesRequest(method="resources/list"))
    return result.root.resources


async def call_read_resource(server: Server, uri: str) -> list:
    handler = server.request_handlers[ReadResourceRequest]
    result = await handler(
        ReadResourceRequest(
            method="resources/read",
            params=ReadResourceRequestParams(uri=AnyUrl(uri)),
        )
    )
    return result.root.contents


# ── create_server ─────────────────────────────────────────────────────────────

def test_create_server_returns_server_instance():
    server = get_server(MINIMAL_DIR)
    assert isinstance(server, Server)


def test_create_server_raises_on_missing_manifest():
    with pytest.raises(Exception):
        create_server(Path("/nonexistent/knowledge.yaml"))


def test_list_resources_handler_registered():
    server = get_server(MINIMAL_DIR)
    assert ListResourcesRequest in server.request_handlers
    assert ReadResourceRequest in server.request_handlers


# ── list_resources ────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_list_resources_minimal_has_manifest_plus_units():
    server = get_server(MINIMAL_DIR)
    resources = await call_list_resources(server)
    # manifest + 1 unit
    assert len(resources) == 2
    names = [r.name for r in resources]
    assert "manifest" in names
    assert "overview" in names


@pytest.mark.asyncio
async def test_list_resources_full_has_all_units():
    server = get_server(FULL_DIR)
    resources = await call_list_resources(server)
    # manifest + 3 units
    assert len(resources) == 4


@pytest.mark.asyncio
async def test_list_resources_agent_only_filters_human_units():
    # full fixture: spec (human+agent+developer), api-schema (developer+agent), guide (human+developer)
    # agent-only: spec + api-schema included; guide excluded
    server = get_server(FULL_DIR, agent_only=True)
    resources = await call_list_resources(server)
    names = [r.name for r in resources]
    assert "manifest" in names
    assert "spec" in names
    assert "api-schema" in names
    assert "guide" not in names


@pytest.mark.asyncio
async def test_list_resources_manifest_has_priority_1():
    server = get_server(MINIMAL_DIR)
    resources = await call_list_resources(server)
    manifest_res = next(r for r in resources if r.name == "manifest")
    assert manifest_res.annotations.priority == 1.0


@pytest.mark.asyncio
async def test_list_resources_unit_uris_use_knowledge_scheme():
    server = get_server(MINIMAL_DIR)
    resources = await call_list_resources(server)
    unit = next(r for r in resources if r.name == "overview")
    assert str(unit.uri).startswith("knowledge://")


# ── read_resource ─────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_read_manifest_returns_json():
    server = get_server(MINIMAL_DIR)
    resources = await call_list_resources(server)
    manifest_res = next(r for r in resources if r.name == "manifest")
    contents = await call_read_resource(server, str(manifest_res.uri))
    assert len(contents) == 1
    assert contents[0].mimeType == "application/json"
    parsed = json.loads(contents[0].text)
    assert parsed["project"] == "my-project"
    assert len(parsed["units"]) == 1


@pytest.mark.asyncio
async def test_read_unit_returns_file_content():
    server = get_server(MINIMAL_DIR)
    resources = await call_list_resources(server)
    unit_res = next(r for r in resources if r.name == "overview")
    contents = await call_read_resource(server, str(unit_res.uri))
    assert len(contents) == 1
    assert "My Project" in contents[0].text


@pytest.mark.asyncio
async def test_read_json_unit_has_correct_mimetype():
    server = get_server(FULL_DIR)
    resources = await call_list_resources(server)
    api_res = next(r for r in resources if r.name == "api-schema")
    contents = await call_read_resource(server, str(api_res.uri))
    assert contents[0].mimeType == "application/schema+json"
    parsed = json.loads(contents[0].text)
    assert "$schema" in parsed


@pytest.mark.asyncio
async def test_read_unknown_unit_raises():
    server = get_server(MINIMAL_DIR)
    with pytest.raises(Exception):
        await call_read_resource(server, "knowledge://my-project/nonexistent")


@pytest.mark.asyncio
async def test_read_wrong_project_uri_raises():
    server = get_server(MINIMAL_DIR)
    with pytest.raises(Exception):
        await call_read_resource(server, "knowledge://other-project/spec")
