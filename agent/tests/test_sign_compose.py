import json
import logging

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


PATH = '/v1/compose-signs'
BASE = {'sessionId': '12345678-1234-1234-1234-123456789abc',
        'segmentId': 'seg-sign-1', 'revision': 1}


def body(*words):
    return {**BASE, 'gestures': [{'candidates': [{'label': word, 'score': 0.8}]}
                                for word in words]}


def upstream(sentence):
    return httpx.Response(200, json={'choices': [{'message': {
        'content': json.dumps({'sentence': sentence}, ensure_ascii=False)}}]})


def client_for(handler, **settings):
    return TestClient(create_app(Settings(api_key='test-key', base_url='https://model.example/v1',
                                          model='test-model', **settings), httpx.MockTransport(handler)))


@pytest.mark.parametrize('words,sentence', [
    (('我', '回', '家'), '我想回家'),
    (('你', '一定', '可以'), '你一定可以'),
    (('你', '要', '照顾', '好', '自己'), '你要照顾好自己'),
    (('祝贺', '大家', '新', '年', '好'), '祝大家新年好'),
    (('我们', '只', '是', '很久不', '见'), '我们只是好久不见'),
])
def test_five_demo_sentences(words, sentence):
    def handler(request):
        payload = json.loads(request.content)
        assert payload['model'] == 'test-model'
        assert payload['enable_thinking'] is False
        assert payload['max_tokens'] == 64
        user = json.loads(payload['messages'][1]['content'])
        assert len(user['allowedSentences']) == 5
        assert sentence in user['allowedSentences']
        assert len(user['gestures']) == len(words)
        return upstream(sentence)
    with client_for(handler) as client:
        response = client.post(PATH, json=body(*words))
        assert response.status_code == 200
        assert response.json()['sentence'] == sentence
        assert response.json()['status'] == 'CANDIDATE'
        assert response.json()['needsConfirmation'] is True
        assert response.json()['segmentId'] == BASE['segmentId']


def test_ranked_candidates_are_forwarded_even_when_evidence_conflicts():
    request = body('你', '一定', '可以')
    request['gestures'][1]['candidates'] = [
        {'label': '要', 'score': 0.5}, {'label': '一定', 'score': 0.4},
        {'label': '好', 'score': 0.1}]
    with client_for(lambda r: upstream('你一定可以')) as client:
        result = client.post(PATH, json=request).json()
        assert result['sentence'] == '你一定可以'
        assert result['status'] == 'CANDIDATE'
        assert '你一定可以' in result['alternatives']


def test_two_third_rank_candidates_do_not_create_spurious_sentence():
    request = {'gestures': [
        {'candidates': [{'label': '我'}, {'label': '照顾'}, {'label': '新'}]},
        {'candidates': [{'label': '回'}, {'label': '你'}, {'label': '好'}]},
        {'candidates': [{'label': '家'}, {'label': '见'}, {'label': '很久不'}]},
    ], **BASE}
    with client_for(lambda r: upstream('我想回家')) as client:
        result = client.post(PATH, json=request).json()
        assert len(result['alternatives']) == 5
        assert result['sentence'] == '我想回家'


def test_one_candidate_still_calls_model_and_selects_one_sentence():
    def handler(request):
        payload = json.loads(request.content)
        options = json.loads(payload['messages'][1]['content'])['allowedSentences']
        assert len(options) == 5
        return upstream('你一定可以')
    with client_for(handler) as client:
        result = client.post(PATH, json=body('你')).json()
        assert result['status'] == 'CANDIDATE'
        assert result['sentence'] == '你一定可以'
        assert len(result['alternatives']) == 5


@pytest.mark.parametrize('invalid', [
    {'gestures': []},
    body('未知'),
    {**BASE, 'gestures': [{'candidates': []}]},
    {**BASE, 'gestures': [{'candidates': [{'label': '你'}] * 2}]},
    {**BASE, 'gestures': [{'candidates': [{'label': '你', 'score': 1.2}]}]},
    {**BASE, 'gestures': [{'candidates': [{'label': '你', 'score': 0.5}], 'extra': 1}]},
    {**BASE, 'gestures': [{'candidates': [{'label': '你'}]}] * 13},
])
def test_invalid_input(invalid):
    with client_for(lambda r: upstream(None)) as client:
        assert client.post(PATH, json={**BASE, **invalid}).status_code == 422


@pytest.mark.parametrize('invalid_choice', [None, '不在五句中的话'])
def test_model_must_choose_one_allowed_sentence(invalid_choice):
    with client_for(lambda r: upstream(invalid_choice)) as client:
        result = client.post(PATH, json=body('我', '回', '家'))
        assert result.status_code == 502
        assert result.json()['error']['code'] == 'MODEL_INVALID_RESPONSE'


def test_authorization_and_openapi():
    with client_for(lambda r: upstream('我想回家'), service_api_key='secret-token') as client:
        assert client.post(PATH, json=body('我', '回', '家')).status_code == 401
        response = client.post(PATH, json=body('我', '回', '家'),
                               headers={'Authorization': 'Bearer secret-token'})
        assert response.status_code == 200
        assert PATH in client.get('/openapi.json').json()['paths']


def test_compose_log_contains_request_and_response_without_token(caplog):
    caplog.set_level(logging.INFO, logger='uvicorn.error')
    with client_for(lambda r: upstream('我想回家'), service_api_key='secret-token') as client:
        response = client.post(PATH, json=body('我', '回', '家'),
                               headers={'Authorization': 'Bearer secret-token'})
    assert response.status_code == 200
    record = next(json.loads(item.message) for item in caplog.records
                  if 'compose_signs_response' in item.message)
    assert record['request']['gestures'][0]['candidates'][0]['label'] == '我'
    assert record['response']['sentence'] == '我想回家'
    assert record['response']['needsConfirmation'] is True
    assert 'secret-token' not in json.dumps(record)


def test_compose_validation_log_identifies_bad_field(caplog):
    caplog.set_level(logging.INFO, logger='uvicorn.error')
    with client_for(lambda r: upstream(None)) as client:
        response = client.post(PATH, json=body('未知'))
    assert response.status_code == 422
    record = next(json.loads(item.message) for item in caplog.records
                  if 'compose_signs_response' in item.message)
    assert record['request']['gestures'][0]['candidates'][0]['label'] == '未知'
    assert record['response']['error']['code'] == 'INVALID_REQUEST'
    assert any('gestures' in item['field'] for item in record['validation_errors'])
