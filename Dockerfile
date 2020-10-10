# Use the official image as a parent image.
FROM adoptopenjdk:11-jre-hotspot
ENV TXNEXAMPLE_VER=0.1.8
#Version: SEE BELOW too for now
ENV RUN_CMD=/opt/app/TransactionsExample-${TXNEXAMPLE_VER}-all.jar
LABEL Description="Couchbase Transactions Java Demo" Vendor="Couchbase, Inc." Version="${TXNEXAMPLE_VER}-all.jar"

# Set the working directory.

# Copy the file from your host to your current location.
RUN mkdir /opt/app
COPY config.toml /opt/app

COPY build/libs/TransactionsExample-${TXNEXAMPLE_VER}-all.jar /opt/app
CMD ["java", "-jar", "/opt/app/TransactionsExample-0.1.8-all.jar", "/opt/app/config.toml"]

# Add metadata to the image to describe which port the container is listening on at runtime.
# Web port
EXPOSE 8080
# Prometheus port
EXPOSE 9000
