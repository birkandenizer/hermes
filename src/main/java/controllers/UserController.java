package controllers;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.servlet.ModelAndView;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;

import domain.Ban;
import domain.Channel;
import domain.Email;
import domain.Log;
import domain.Message;
import domain.Session;
import domain.User;
import domain.Sms;
import domain.TempUser;
import domain.FavMessages;
import domain.FavChannels;

import repositories.BanRepository;
import repositories.ChannelRepository;
import repositories.MessageRepository;
import repositories.NotificationRepository;
import repositories.SessionRepository;
import repositories.TempUserRepository;
import repositories.UserRepository;
import repositories.FavMessagesRepository;
import repositories.FavChannelsRepository;
import services.AsyncMail;
import services.AsyncSms;
import services.BanService;
import services.ChannelService;
import services.FavChannelsService;
import services.FavMessagesService;
import services.LogService;
import services.MessageService;
import services.NotificationService;
import services.SessionService;
import services.TempUserService;
import services.UserService;

@Controller
@SessionAttributes({"login", "notifications"})
public class UserController {
	@Autowired
	private UserService userService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private TempUserService tempUserService;
	@Autowired
	private TempUserRepository tempUserRepository;
	@Autowired
	private ChannelService channelService;
	@Autowired
	private ChannelRepository channelRepository;
	@Autowired
	private LogService logService;
	@Autowired
	private MessageService messageService;
	@Autowired
	private MessageRepository messageRepository;
	@Autowired
	private SessionRepository sessionRepository;
	@Autowired
	private SessionService sessionService;
	@Autowired
	private FavMessagesService favMessagesService;
	@Autowired
	private FavMessagesRepository favMessagesRepository;
	@Autowired
	private FavChannelsService favChannelsService;
	@Autowired
	private FavChannelsRepository favChannelsRepository;
	@Autowired
	private NotificationRepository notificationRepository;
	@Autowired
	private NotificationService notificationService;

	
	@Autowired
	private AsyncMail asyncMail;
	@Autowired
	private AsyncSms asyncSms;
	@Autowired
	private BanService banService;
	@Autowired
	private BanRepository banRepository;

	@Autowired
	private ChannelController channelController;
	@Autowired
	private MessageController messageController;
	
	@RequestMapping(value = "/test/createUser", method = RequestMethod.POST)
    public String createUser(@ModelAttribute("user")TempUser user, ModelMap model, HttpServletRequest request) {
		User newuser = userRepository.findByUsername(user.getUsername());
		Image image = null;
		try {
			image = ImageIO.read(new File(user.getProfilePicture()));
		} catch (IOException e) {
			e.printStackTrace();
		}
		if(image == null){
			user.setProfilePicture("/resources/avatar.jpg");
		}
		if(newuser != null) {
			//username is taken
			Log newlog = new Log("Could not create user with username: " + user.getUsername() + "as it was taken");
			logService.saveOrUpdate(newlog);
			
			return "redirect:/test/home";
		}
		newuser = userRepository.findByEmail(user.getEmail());
		if(newuser != null) {
			//email is taken
			Log newlog = new Log("Could not create user with Email: " + user.getEmail() + "as it was taken");
			logService.saveOrUpdate(newlog);
			
			return "redirect:/test/home";
		}
		
		user.setConfirmationToken(UUID.randomUUID().toString());
		
		TempUser tempnewuser = tempUserService.saveOrUpdate(user);
        
		String appUrl = "https://" + request.getServerName() + ":5001";
		
		try {
			String mailText = "Welcome to Hermes Anonymous Messaging.\n To confirm your e-mail address, please click the link below:\n"
					+ appUrl + "/confirm?token=" + user.getConfirmationToken();
			asyncMail.sendMailWithTitle(user.getEmail(),mailText,"Registration Confirmation");
		}catch( Exception e ){
			// catch error
		}
		
		Log newlog = new Log("Created a new temporary user with username: " + user.getUsername() + " and Email: " + user.getEmail());
		logService.saveOrUpdate(newlog);
		
        return "newRegistry";
    }
	
	@RequestMapping(value="/confirm", method = RequestMethod.GET)
	public String showConfirmationPage(ModelMap model, @RequestParam("token") String token) {
			
		TempUser newUser = tempUserRepository.findByConfirmationToken(token);
		
		if (newUser == null) { // No token found in DB
			model.addAttribute("verificationMessage", "Oops!  This is an invalid confirmation link.");
			
			Log newlog = new Log("Could not find confirmation token with ID : " + token);
			logService.saveOrUpdate(newlog);
			
			return "confirm";
			
		} else { // Token found
			
			List<TempUser> users = tempUserRepository.findByEmail(tempUserRepository.findByConfirmationToken(token).getEmail());
			
			for(TempUser currentUser : users) {
				tempUserRepository.delete(currentUser.id);
			}
			
			/*if(tempUserRepository.findByConfirmationToken(token).id != null) {
				tempUserRepository.delete(tempUserRepository.findByConfirmationToken(token).id);
			}*/
			User shadow = new User(newUser.getFirstName(), newUser.getLastName(), newUser.getEmail(), newUser.getPassword(), newUser.getUsername(), newUser.getPhoneNumber(), newUser.getProfilePicture());
			shadow.setConfirmationToken(newUser.getConfirmationToken());
			userService.saveOrUpdate(shadow);
			
			Log newlog = new Log("Successfully activated account with username: " + newUser.getUsername() + " and Email: " + newUser.getEmail());
			logService.saveOrUpdate(newlog);
			
			model.put("login",shadow);
			
			return "redirect:/test/profile";
		}	
	}
	
	@RequestMapping(value = "/test/login", method = RequestMethod.POST)
    public String login(@RequestParam (value="username") String username,
			    		@RequestParam (value="password") String password
						, Locale locale, ModelMap model) throws NoSuchAlgorithmException {
		User newuser = userRepository.findByUsername(username);
		//username check
		if(newuser == null) {
			Log newlog = new Log("Could not find a user with username: " + username + "to login");
			logService.saveOrUpdate(newlog);
		
			return "redirect:/test/home";
		}
		
		//password check
		BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
		boolean result = passwordEncoder.matches(password, newuser.getPassword());
		if(!result) {
			Log newlog = new Log("Could not log in a user with username: " + username + "because of wrong password");
			logService.saveOrUpdate(newlog);
			
			return "redirect:/test/home";
		}
		
        model.put("login",newuser);
        Log newlog = new Log("Logged in a user with username: " + username);
		logService.saveOrUpdate(newlog);

		model.put("notifications", notificationService.getByIdWithNames(((User) model.get("login")).getId()));
        return "redirect:/test/profile";
    }

	@RequestMapping(value = "/test/changePrivacy", method = RequestMethod.POST)
	public String changeProfilePrivacy(@RequestParam (value="userId") String userId, Locale locale, ModelMap model) {
		User current = userRepository.findOne(userId);
		//username check
		if(current == null) {
			Log newlog = new Log("Could not find the user: " + userId);
			logService.saveOrUpdate(newlog);

			return "redirect:/test/home";
		}

		if(((User)model.get("login")).getId().equals(current.getId())){
			current.setPrivateProfile(!current.getPrivateProfile());
		}
		model.put("login",current);
		userService.saveOrUpdate(current);

		Log newlog = new Log("User \"" + userId +"\" has changed their privacy setting");
		logService.saveOrUpdate(newlog);

		model.put("notifications", notificationService.getByIdWithNames(((User) model.get("login")).getId()));
		return "redirect:/test/profile";
	}

	@RequestMapping(value = "/test/logout", method = RequestMethod.GET)
	public String logout(Locale locale, ModelMap model) {
		if(model.get("login") == null) {
			Log newlog = new Log("Could not log out because no one is logged in");
			logService.saveOrUpdate(newlog);
			
			return "redirect:/test/home";
		}
		else {
			model.remove("login");
			model.remove("notifications");
		}
		Log newlog = new Log("Logged out current user");
		logService.saveOrUpdate(newlog);
		
		return "redirect:/test/home";
	}

	@RequestMapping(value = "/test/deleteProfile", method = RequestMethod.POST)
	public String deleteProfile(@ModelAttribute("user") User user, ModelMap model) {

		User current = userService.getById(((User) model.get("login")).getId());
		Channel channel;

		for (String channel1Id : current.getChannelsList()) {
			channel = channelService.getById(channel1Id);
			if (channel != null) {
				if (channel.getOwnerId().equals(current.getId())) { // if owner then delete all members from channels
					channelController.channelDeleter(current.getId(), channel.getId());
				} else {  // else delete only current from channels
					messageController.changeActivityOfUserMessages(current.getId(),channel.getId(),false);
					channel.removeMember(current.getId());
					channelService.saveOrUpdate(channel);
				}
			}
		}

		userService.delete(current.getId());

		String username = current.getUsername();
		Log newlog = new Log("Delete user with the username " + username);
		logService.saveOrUpdate(newlog);

		return "redirect:/test/home";
	}
	
	@RequestMapping(value = "/test/profile/{query}", method = RequestMethod.GET)
	public String testProfile(@PathVariable("query") String query, Locale locale, ModelMap model) throws JsonProcessingException {
		
		User current = userService.getById(((User) model.get("login")).getId());
		
		List<Channel> otherJoinedChannels = new ArrayList<Channel>();
		List<Channel> otherMyChannels = new ArrayList<Channel>();
		List<Channel> otherFavouriteChannels = new ArrayList<Channel>();
		List<FavChannels> favouriteChannelsList = new ArrayList<FavChannels>();
		
		List<Channel> joinedChannels = new ArrayList<Channel>();
		List<Channel> myChannels = new ArrayList<Channel>();
		
		User otherProfile = userRepository.findByUsername(query);
		model.put("notifications", notificationService.getByIdWithNames(((User) model.get("login")).getId()));
		Channel chnl;
		for(String channel1Id : current.getChannelsList()) {
			chnl = channelService.getById(channel1Id);
			if(chnl.getOwnerId().equals(current.getId())) {
				myChannels.add(chnl);

			}else {
				joinedChannels.add(chnl);

			}

		}
		model.addAttribute("joinedChannels",joinedChannels);
		model.addAttribute("mychannels",myChannels);
		List<Session> session = new ArrayList<Session>();
		session = sessionService.listAll();

		model.addAttribute("sessionList",session);


		//BEKOSHOW
		HashMap<String,String> channelNames = new HashMap<String,String>();
		session = new ArrayList<Session>();
		myChannels = new ArrayList<Channel>();
		for(String channelid : otherProfile.getChannelsList()) {
			chnl = channelService.getById(channelid);
			for(String sesid : chnl.getSessionsList()) {
				session.add(sessionService.getById(sesid));
				channelNames.put(sesid, chnl.getName());
			}
		}
		ObjectMapper objectMapper = new ObjectMapper();
		model.addAttribute("bekoSessions",objectMapper.writeValueAsString(session));
		model.addAttribute("bekoChannelNames",objectMapper.writeValueAsString(channelNames));

		//CANT ACCESS PROFILE
		if(otherProfile.getPrivateProfile())
			return "privateProfile";

		favouriteChannelsList = favChannelsService.getByUserId(otherProfile.getId());
		
		for(FavChannels msg : favouriteChannelsList) {
			otherFavouriteChannels.add(channelService.getById(msg.getchannelId()));
		}
		
		Channel channel;
		for(String channel1Id : otherProfile.getChannelsList()) {
			channel = channelService.getById(channel1Id);
			if(channel.getOwnerId().equals(otherProfile.getId())) {
				otherMyChannels.add(channel);
			}else {
				otherJoinedChannels.add(channel);
			}
		}
		

		
		model.addAttribute("otherfavouriteChannels",otherFavouriteChannels);
		model.addAttribute("othermychannels",otherMyChannels);
		model.addAttribute("otherjoinedChannels",otherJoinedChannels);
		model.addAttribute("profile", otherProfile);
		

		
		Log newlog = new Log("Accessed to profile of user with ID: " + otherProfile.id);
		logService.saveOrUpdate(newlog);

		return "otherProfile";
	}
	
	@RequestMapping(value = "/test/profile", method = RequestMethod.GET)
	public String testProfile(Locale locale, ModelMap model) throws JsonProcessingException {
		if(model.get("login") == null) {
			Log newlog = new Log("Could not access to profile because user is not logged in");
			logService.saveOrUpdate(newlog);
			
			return "redirect:/test/home";
		}
		User current = userService.getById(((User) model.get("login")).getId());
		if(current == null) {
			//SESSION - DB CONFLICT
			Log newlog = new Log("Could not access to profile because there exist a session-database conflict");
			logService.saveOrUpdate(newlog);
			
			return "redirect:/test/home";
		}
		List<Channel> joinedChannels = new ArrayList<Channel>();
		List<Channel> myChannels = new ArrayList<Channel>();
		List<Channel> favouriteChannels = new ArrayList<Channel>();
		
		List<FavChannels> favouriteChannelsList = new ArrayList<FavChannels>();
		
		favouriteChannelsList = favChannelsService.getByUserId(current.getId());
		
		for(FavChannels msg : favouriteChannelsList) {			
			favouriteChannels.add(channelService.getById(msg.getchannelId()));			
		}

		
		Channel channel;
		for(String channel1Id : current.getChannelsList()) {
			channel = channelService.getById(channel1Id);
			if(channel.getOwnerId().equals(current.getId())) {
				myChannels.add(channel);

			}else {
				joinedChannels.add(channel);
				
			}

		}
		
		model.addAttribute("favouriteChannels",favouriteChannels);
		model.addAttribute("mychannels",myChannels);
		model.addAttribute("joinedChannels",joinedChannels);
		model.addAttribute("profile", current);
		List<Session> session = new ArrayList<Session>();
		session = sessionService.listAll();
		
		model.addAttribute("sessionList",session);
		
		
		//BEKOSHOW
		HashMap<String,String> channelNames = new HashMap<String,String>();
		session = new ArrayList<Session>();
		myChannels = new ArrayList<Channel>();
		for(String channelid : current.getChannelsList()) {
			channel = channelService.getById(channelid);
			for(String sesid : channel.getSessionsList()) {
				session.add(sessionService.getById(sesid));
				channelNames.put(sesid, channel.getName());
			}
		}
		ObjectMapper objectMapper = new ObjectMapper();
		model.addAttribute("bekoSessions",objectMapper.writeValueAsString(session));
		model.addAttribute("bekoChannelNames",objectMapper.writeValueAsString(channelNames));
		
		
		
		
		Log newlog = new Log("Accessed to profile of user with ID: " + current.id);
		logService.saveOrUpdate(newlog);

		model.put("notifications", notificationService.getByIdWithNames(((User) model.get("login")).getId()));
		return "profile";
	}
	
}
