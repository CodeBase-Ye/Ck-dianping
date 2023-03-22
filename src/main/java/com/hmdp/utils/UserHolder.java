package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    static UserDTO userDto = new UserDTO();


    public static void saveUser(UserDTO user){
        userDto = user;
        tl.set(user);
    }

    public static UserDTO getUser(){
        //return tl.get();
        return userDto;
    }

    public static void removeUser(){
        tl.remove();
    }
}


