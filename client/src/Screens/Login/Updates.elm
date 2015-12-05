module Screens.Login.Updates where

import Task exposing (Task, succeed, map, andThen)
import Result exposing (Result(Ok, Err))
import Effects exposing (Effects, Never, none)

import AppTypes exposing (..)
import Screens.Login.Types exposing (..)
import ServerApi
import Screens.UpdateUtils as Utils


addr : Signal.Address Action
addr =
  Utils.screenAddr LoginAction


mount : (Screen, Effects Action)
mount =
  initial &: none


update : Action -> Screen -> (Screen, Effects Action)
update action screen =
  case action of

    SetEmail e ->
      { screen | email = e } &: none

    SetPassword p ->
      { screen | password = p } &: none

    Submit ->
      { screen | loading = True } &! (submitTask screen)

    SubmitResult result ->
      case result of
        Ok player ->
          let
            newScreen = { screen | loading = False, error = False }
            effect = Utils.setPlayer player |> Utils.always NoOp
          in
            newScreen &: effect
        Err formErrors ->
          { screen | loading = False, error = True } &: none

    NoOp ->
      screen &: none

submitTask : Screen -> Task Never Action
submitTask screen =
  ServerApi.postLogin screen.email screen.password
    |> Task.map SubmitResult

