repo_root := `pwd`

alias fmt := format
alias u := update

[private]
list:
    @# First command in the file is invoked by default
    @just --list

format:
    just --fmt --unstable
    nixfmt flake.nix
    npx --no @chrisoakman/standard-clojure-style fix

# Update dependencies
update:
    nix flake update
    clj -M:antq --exclude=org.clojure/clojure --upgrade --force
