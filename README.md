# Artemis - Web Content Keyword Extractor

웹사이트의 핵심 콘텐츠를 분석하고 SEO용 키워드를 추출하는 서비스입니다.

## Features

- **Selenium + Chrome WebDriver**: 동적 웹페이지 렌더링 지원
- **Mozilla Readability 스타일 콘텐츠 추출**: 불필요한 요소 제거 후 핵심 콘텐츠만 추출
- **스크린샷 기반 LLM OCR 분석**: Vision API를 활용한 시각적 콘텐츠 분석
- **LLM 기반 키워드 추출**: AI 분석을 통한 SEO 최적화 키워드 추천
- **REST API**: 간편한 API 인터페이스

## Tech Stack

- **Language**: Kotlin 1.9
- **Framework**: Spring Boot 3.2
- **Browser Automation**: Selenium WebDriver 4.17
- **HTML Parsing**: Jsoup 1.17
- **HTTP Client**: OkHttp 4.12
- **LLM Integration**: OpenAI API (GPT-4o)

## Quick Start

### Prerequisites

- JDK 17+
- Docker & Docker Compose
- LLM API Key (OpenAI, Anthropic, etc.)

### Using Docker Compose

1. 환경변수 설정:
```bash
cp .env.example .env
# .env 파일을 편집하여 LLM_API_KEY 설정
```

2. 서비스 시작:
```bash
docker-compose up -d
```

3. API 호출:
```bash
curl -X POST http://localhost:8080/api/v1/analyze \
  -H "Content-Type: application/json" \
  -d '{"url": "https://example.com"}'
```

### Local Development

1. Gradle 빌드:
```bash
./gradlew build
```

2. Selenium 서버 실행:
```bash
docker run -d -p 4444:4444 -p 7900:7900 --shm-size=2g selenium/standalone-chrome:120.0
```

3. 애플리케이션 실행:
```bash
export LLM_API_KEY=your-api-key
./gradlew bootRun
```

## API Endpoints

### POST /api/v1/analyze
웹페이지 분석 및 키워드 추출

**Request:**
```json
{
  "url": "https://example.com",
  "extractionMethod": "AUTO",  // AUTO, READABILITY, SCREENSHOT_OCR, HYBRID
  "language": "auto"
}
```

**Response:**
```json
{
  "url": "https://example.com",
  "title": "Example Domain",
  "description": "...",
  "summary": "This page is about...",
  "keywords": [
    {"term": "example", "relevance": 0.95, "category": "PRIMARY"},
    {"term": "domain", "relevance": 0.85, "category": "SECONDARY"}
  ],
  "extractedContent": {...},
  "metadata": {...},
  "analyzedAt": "2024-01-01T00:00:00Z"
}
```

### GET /api/v1/extract?url=...
콘텐츠만 추출 (LLM 분석 없음)

### GET /api/v1/metadata?url=...
메타데이터만 추출

### POST /api/v1/analyze/batch
여러 URL 일괄 분석

### GET /api/v1/health
헬스체크

## Configuration

### application.yml

```yaml
selenium:
  chrome:
    remote-url: http://localhost:4444/wd/hub
    headless: true
    timeout-seconds: 30

llm:
  provider: openai
  api-key: ${LLM_API_KEY}
  model: gpt-4o
  base-url: https://api.openai.com/v1

extraction:
  min-text-length: 50
  max-content-length: 50000
  screenshot:
    enabled: true
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `LLM_API_KEY` | LLM API 키 | - |
| `LLM_PROVIDER` | LLM 제공자 (openai, anthropic, ollama) | openai |
| `LLM_MODEL` | 사용할 모델 | gpt-4o |
| `LLM_BASE_URL` | API Base URL | https://api.openai.com/v1 |
| `SELENIUM_REMOTE_URL` | Selenium 서버 URL | http://localhost:4444/wd/hub |

## Extraction Methods

### AUTO (기본값)
Readability 알고리즘을 먼저 시도하고, 결과가 충분하지 않으면 Hybrid 방식으로 전환합니다.

### READABILITY
Mozilla Readability 스타일의 알고리즘으로 HTML에서 핵심 콘텐츠를 추출합니다.
- 네비게이션, 사이드바, 푸터 등 제거
- 콘텐츠 밀도 기반 스코어링
- 가장 빠른 처리 속도

### SCREENSHOT_OCR
페이지 스크린샷을 LLM Vision API로 분석합니다.
- JavaScript 렌더링된 콘텐츠 캡처
- 시각적 레이아웃 고려
- 이미지 기반 콘텐츠 분석 가능

### HYBRID
두 방식을 결합하여 최상의 결과를 도출합니다.
- Readability 결과가 부족할 경우 스크린샷 분석으로 보완
- 가장 정확하지만 처리 시간이 길어질 수 있음

## License

MIT License
