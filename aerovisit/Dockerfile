# AeroVisit (Spring Boot) for Elastic Beanstalk's Docker platform.
# Ubuntu 24.04 + Java 21 with Tesseract OCR for the KYC name check: Amazon Linux 2023 (EB's Java platform) has no
# Tesseract package. The app itself is unchanged; it reads the OCR data folder from TESSDATA_PATH.
# Build context = the deploy zip: this Dockerfile + aerovisit.jar (the Spring Boot jar from target/) + .platform/
FROM eclipse-temurin:21-jre-noble

# Tesseract 5.3 + English data. The -dev packages add the plain libtesseract.so / libleptonica.so names that tess4j
# (JNA) loads. tzdata so TZ below works; fontconfig + DejaVu for anything Java renders with fonts (PDF exports).
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      tesseract-ocr tesseract-ocr-eng libtesseract-dev libleptonica-dev tzdata fontconfig fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*

# 🕒 Manila time from the very first moment, so the MySQL driver never sees UTC (dates were saved a day early)
ENV TZ=Asia/Manila \
    TESSDATA_PATH=/usr/share/tesseract-ocr/5/tessdata \
    SERVER_PORT=5000

# Runs as a normal user, not root; uploads/ is its local cache (the real copies live in S3, see UploadMirror)
RUN useradd --system --create-home aerovisit && mkdir -p /app/uploads && chown -R aerovisit /app
WORKDIR /app
COPY --chown=aerovisit aerovisit.jar app.jar
USER aerovisit

EXPOSE 5000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
