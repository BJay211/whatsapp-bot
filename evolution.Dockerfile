FROM evapicloud/evolution-api:latest
EXPOSE 8080
CMD ["node", "dist/main.js"]