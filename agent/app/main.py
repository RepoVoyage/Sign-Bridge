from contextlib import asynccontextmanager
import json
import logging
import secrets
from typing import Annotated

import httpx
from fastapi import Depends, FastAPI, Header, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import Settings
from .schemas import (ErrorResponse, ErrorDetail, HealthResponse, PolishRequest, PolishResponse,
                      SignComposeRequest, SignComposeResponse)
from .service import ServiceError, polish
from .sign_compose import compose_signs


logger = logging.getLogger('uvicorn.error')


def compose_request_summary(body):
    if isinstance(body, SignComposeRequest):
        return body.model_dump(exclude_none=True)
    if not isinstance(body, dict):
        return {'body_type': type(body).__name__}
    summary = {}
    for key in ('sessionId', 'segmentId', 'revision'):
        value = body.get(key)
        summary[key] = value[:128] if isinstance(value, str) else value
    gestures = body.get('gestures')
    if isinstance(gestures, list):
        summary['gesture_count'] = len(gestures)
        summary['gestures'] = []
        for gesture in gestures[:12]:
            candidates = gesture.get('candidates') if isinstance(gesture, dict) else None
            if not isinstance(candidates, list):
                summary['gestures'].append({'candidates_type': type(candidates).__name__})
                continue
            summary['gestures'].append({
                'candidate_count': len(candidates),
                'candidates': [
                    {key: value[:128] if isinstance(value, str) else value
                     for key, value in candidate.items() if key in ('label', 'score')}
                    if isinstance(candidate, dict) else {'candidate_type': type(candidate).__name__}
                    for candidate in candidates[:3]
                ],
            })
    else:
        summary['gestures_type'] = type(gestures).__name__
    return summary


def log_compose_response(request: Request, http_status: int, body,
                         validation_errors: list[dict] | None = None):
    if request.url.path != '/v1/compose-signs':
        return
    record = {'event': 'compose_signs_response', 'http_status': http_status,
              'request': compose_request_summary(getattr(request.state, 'compose_body', None)),
              'remaining_budget_ms': request.headers.get('x-remaining-budget-ms'),
              'response': body.model_dump() if hasattr(body, 'model_dump') else body}
    if validation_errors is not None:
        record['validation_errors'] = [
            {'field': '.'.join(str(part) for part in item['loc']), 'type': item['type']}
            for item in validation_errors
        ]
    logger.info('%s', json.dumps(record, ensure_ascii=False))


def error_response(status, code, message, retryable, segment_id=None, revision=None):
    body = ErrorResponse(segmentId=segment_id, revision=revision,
                         error=ErrorDetail(code=code, message=message, retryable=retryable))
    return JSONResponse(status_code=status, content=body.model_dump())


def create_app(settings: Settings | None = None, transport=None) -> FastAPI:
    config = settings if settings is not None else Settings.from_env()

    @asynccontextmanager
    async def lifespan(app):
        async with httpx.AsyncClient(transport=transport, follow_redirects=False) as client:
            app.state.client = client
            yield

    app = FastAPI(title='随心说 Language Processing API', version='2.1.0', lifespan=lifespan)
    bearer = HTTPBearer(auto_error=False)

    async def authorize(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]):
        if config.service_api_key and (credentials is None or not secrets.compare_digest(
            credentials.credentials.encode(), config.service_api_key.encode()
        )):
            raise ServiceError(401, 'UNAUTHORIZED', '请提供有效的服务访问令牌。', False)

    @app.exception_handler(ServiceError)
    async def service_error(request: Request, exc: ServiceError):
        response = error_response(exc.http_status, exc.code, exc.message, exc.retryable)
        log_compose_response(request, exc.http_status, json.loads(response.body))
        return response

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request: Request, exc: RequestValidationError):
        request.state.compose_body = exc.body
        response = error_response(422, 'INVALID_REQUEST', '请检查请求字段类型、格式和长度。', False)
        log_compose_response(request, 422, json.loads(response.body), exc.errors())
        return response

    @app.exception_handler(Exception)
    async def unexpected_error(request: Request, exc: Exception):
        response = error_response(500, 'INTERNAL_ERROR', '服务内部错误。', False)
        log_compose_response(request, 500, json.loads(response.body))
        return response

    @app.get('/health', response_model=HealthResponse)
    async def health():
        return HealthResponse(model_configured=config.configured)

    @app.post('/v1/polish', response_model=PolishResponse, dependencies=[Depends(authorize)],
              responses={code: {'model': ErrorResponse} for code in (401, 422, 500, 502, 503, 504)})
    async def polish_endpoint(
        body: PolishRequest, request: Request,
        x_remaining_budget_ms: Annotated[int, Header(ge=1, le=10000,
            description='可选：客户端发送时剩余预算；手机仍须执行从 FINAL 起算的总期限。')] = 10000,
    ):
        try:
            result = await polish(body, config, request.app.state.client,
                                  min(config.timeout_seconds, x_remaining_budget_ms / 1000, 10))
        except ServiceError as exc:
            return error_response(exc.http_status, exc.code, exc.message, exc.retryable,
                                  body.segmentId, body.revision)
        return PolishResponse(segmentId=body.segmentId, revision=body.revision, **result.model_dump())

    @app.post('/v1/compose-signs', response_model=SignComposeResponse, dependencies=[Depends(authorize)],
              responses={code: {'model': ErrorResponse} for code in (401, 422, 500, 502, 503, 504)})
    async def compose_signs_endpoint(
        body: SignComposeRequest, request: Request,
        x_remaining_budget_ms: Annotated[int, Header(ge=1, le=10000,
            description='客户端剩余处理预算，单位毫秒；云端调用最多 10 秒。')] = 10000,
    ):
        request.state.compose_body = body
        try:
            sentence, alternatives, status = await compose_signs(
                body, config, request.app.state.client,
                min(config.timeout_seconds, x_remaining_budget_ms / 1000, 10))
        except ServiceError as exc:
            response = error_response(exc.http_status, exc.code, exc.message, exc.retryable,
                                      body.segmentId, body.revision)
            log_compose_response(request, exc.http_status, json.loads(response.body))
            return response
        result = SignComposeResponse(segmentId=body.segmentId, revision=body.revision,
                                     sentence=sentence, alternatives=alternatives, status=status)
        log_compose_response(request, 200, result)
        return result

    return app


app = create_app()
