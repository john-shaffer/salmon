repo_root := `pwd`

alias u := update

[private]
list:
    @# First command in the file is invoked by default
    @just --list

# Update dependencies
update:
    nix flake update
    clj -M:antq --exclude=org.clojure/clojure --upgrade --force
