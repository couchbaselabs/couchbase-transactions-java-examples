# Use the official image as a parent image.
#FROM openjdk:11
FROM gradle:jdk11 as builder

# Set the working directory.
WORKDIR /usr/src/app

# Copy the file from your host to your current location.
COPY config.toml .
COPY src/ /src/
#COPY gradle/ /gradle/
#COPY gradlew .

# Run the command inside your image filesystem.
RUN gradle build
CMD gradle bootRun -Pargs='config.toml'

# Add metadata to the image to describe which port the container is listening on at runtime.
EXPOSE 8080

# Run the specified command within the container.
#CMD [ "npm", "start" ]

# Copy the rest of your app's source code from your host to your image filesystem.
COPY . .
