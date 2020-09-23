package example.docker;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
public class WebController {

	@RequestMapping("/")
	public String index() {
		return "<html>" +
				"<head>" +
				"<title>Transactions</title>" +
				"</head>" +
				"<body>" +
				"<p style='text-align: center; padding: 200px 0; \n" +
				"    font-family: sans-serif;\n" +
				"    font-size: 30em;\n" +
				"    color: #339989;'>" + Integer.toString(Application.transactionCount.get()) + "</p" +
				"</body>" +
				"</html>";
	}

}