# Use the official image as a parent image.
FROM gradle:jdk11 as builder
LABEL Description="Couchbase Transactions Java Demo" Vendor="Couchbase, Inc." Version="0.1"

# Set the working directory.
WORKDIR /usr/src/app

# Copy the file from your host to your current location.
COPY config.toml .
COPY src/ src/
COPY build.gradle .
COPY settings.gradle .

# Run the command inside your image filesystem.
RUN gradle shadowJar
CMD java -jar build/libs/TransactionsExample-1.0.0-all.jar config.toml

# Add metadata to the image to describe which port the container is listening on at runtime.
# Web port
EXPOSE 8080
# Prometheus port
EXPOSE 9000
