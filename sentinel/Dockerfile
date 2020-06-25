FROM redis:6.0-alpine

EXPOSE 5000
COPY entrypoint.sh /usr/local/bin/sentinel-entrypoint.sh
ENTRYPOINT ["sentinel-entrypoint.sh"]
