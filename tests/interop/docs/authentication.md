# Authentication Guide

## OAuth2

Request a token from `POST /oauth/token` with your client credentials.
Include the token as `Authorization: Bearer <token>` on every request.

## API Keys

Set `X-API-Key: <your-key>` in the request header.
Generate keys at https://example.com/settings/api-keys.
