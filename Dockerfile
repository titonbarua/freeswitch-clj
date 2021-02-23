FROM clojure
RUN mkdir -p /usr/src/app
WORKDIR /usr/src/app
COPY project.clj /usr/src/app
RUN lein deps
RUN lein with-profile test deps
COPY . /usr/src/app
CMD ["lein", "test"]
