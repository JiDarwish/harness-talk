from dataclasses import dataclass
from typing import Generic, TypeVar

T = TypeVar("T")
E = TypeVar("E")


@dataclass(frozen=True)
class Success(Generic[T]):
    value: T


@dataclass(frozen=True)
class Failure(Generic[E]):
    error: E


type Result[T, E] = Success[T] | Failure[E]


def success(value):
    return Success(value)


def failure(error):
    return Failure(error)
