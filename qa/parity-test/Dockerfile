FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        default-mysql-client \
        postgresql-client \
        curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY parity-test/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY parity-test/ .

RUN mkdir -p /results

CMD ["python", "-m", "pytest", "tests/", "-v", "--tb=short", "--json-report", "--json-report-file=/results/report.json"]
