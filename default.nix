{ pkgs ? import <nixpkgs> { } }:

with pkgs;

# channel=23.05
mkShell {
  buildInputs = [
    clojure
    leiningen
  ];
}
