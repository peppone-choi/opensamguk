# 공개 황실 court 읽기

K8-10 소비 답과 D123 공개 범위의 작은 HTTP 읽기 준비다. ACTIVE는 황제·조정 城·섭정·지키는 세력을 공개하고 VACANT/ENDED는 code/name/status만 보인다. nullable 상세 키는 명시 null·칸별 NOT_APPLICABLE로 보존한다. 미시드는 NOT_SEEDED이며 VACANT로 대체하지 않는다.

최초 회귀가 기존 codec의 ACTIVE/공위/종결 projection을 검증한다. 구현과 실제 HTTP/auth/참조 시험은 후속 커밋에서 완성한다. 기존 익명 presence는 H03 위치를 계속 제공하며 두 HTTP 요청의 원자성을 주장하지 않는다. 다른 황실 자료·인장·미응답 조서·수치/관계·운영 seed 공개는 없다.
